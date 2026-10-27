param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'
$taskOwnerSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$taskStudentSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$taskOwnerHeaders = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('author:demo-pass')) }
$taskStudentHeaders = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('student1:demo-pass')) }
function Call-PostApi([string]$Method, [string]$Path, [bool]$Owner, $Body = $null) {
    $taskSession = if ($Owner) { $taskOwnerSession } else { $taskStudentSession }
    $taskHeaders = if ($Owner) { $taskOwnerHeaders } else { $taskStudentHeaders }
    $taskParams = @{ Method = $Method; Uri = "$BaseUrl$Path"; WebSession = $taskSession; Headers = $taskHeaders }
    if ($null -ne $Body) {
        $taskParams.ContentType = 'application/json; charset=utf-8'
        $taskParams.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress))
    }
    Invoke-RestMethod @taskParams
}
function Assert-Post([bool]$Condition, [string]$Message) { if (!$Condition) { throw "검증 실패: $Message" } }
$taskOwnerToken = Call-PostApi GET '/api/csrf' $true
$taskStudentToken = Call-PostApi GET '/api/csrf' $false
$taskOwnerHeaders[$taskOwnerToken.data.headerName] = $taskOwnerToken.data.token
$taskStudentHeaders[$taskStudentToken.data.headerName] = $taskStudentToken.data.token
# A new minute per invocation keeps this repeatable while retaining server conflict checks.
$taskExisting = Call-PostApi GET '/api/posts?status=ALL&size=50' $true
$taskTime = [DateTimeOffset]::UtcNow.AddHours(4)
$taskTime = $taskTime.AddSeconds(-$taskTime.Second).AddTicks(-($taskTime.Ticks % [TimeSpan]::TicksPerSecond))
while (@($taskExisting.data.items | Where-Object { [DateTimeOffset]::Parse($_.referenceTime) -eq $taskTime }).Count -gt 0) { $taskTime = $taskTime.AddMinutes(1) }
$taskBody = @{ type = 'TAXI'; title = 'HTTP 모집 검증'; content = '함께 이동해요'; capacity = 4; referenceTime = $taskTime.ToString('o'); departure = '석계역'; destination = '삼육대학교'; communicationUrl = 'https://example.com/room' }
$taskCreated = Call-PostApi POST '/api/posts' $true $taskBody
$taskId = $taskCreated.data.postId
Assert-Post ($taskCreated.data.currentMemberCount -eq 1 -and $taskCreated.data.viewer.isAuthor) '작성자 1명 / 인증된 작성자'
$taskPublic = Call-PostApi GET "/api/posts/$taskId" $false
Assert-Post ($null -eq $taskPublic.data.communicationUrl -and $taskPublic.data.viewer.canJoin) '비참가자 링크 비노출'
$taskEdited = Call-PostApi PATCH "/api/posts/$taskId" $true @{ title = '수정 완료'; capacity = 3 }
Assert-Post ($taskEdited.data.title -eq '수정 완료' -and $taskEdited.data.capacity -eq 3) '작성자 수정'
$taskJoined = Call-PostApi POST "/api/posts/$taskId/participants" $false
$taskJoinedView = Call-PostApi GET "/api/posts/$taskId" $false
Assert-Post ($taskJoinedView.data.currentMemberCount -eq 2 -and $null -ne $taskJoinedView.data.communicationUrl) '참여 인원 / 참가자 링크'
try { $null = Call-PostApi PATCH "/api/posts/$taskId" $true @{ capacity = 2 }; throw '핵심 조건 변경을 허용했습니다.' }
catch { if ($_.Exception.Response.StatusCode.value__ -ne 409) { throw } }
try { $null = Call-PostApi POST '/api/posts' $true $taskBody; throw '중복 모집을 허용했습니다.' }
catch { if ($_.Exception.Response.StatusCode.value__ -ne 409) { throw } }
$taskList = Call-PostApi GET '/api/posts?type=TAXI&page=0&size=50' $false
Assert-Post (@($taskList.data.items | Where-Object postId -eq $taskId).Count -eq 1) '유형 목록 조회'
$null = Call-PostApi DELETE "/api/posts/$taskId/participants" $false
$taskFinal = Call-PostApi GET "/api/posts/$taskId" $false
Assert-Post ($taskFinal.data.currentMemberCount -eq 1 -and $null -eq $taskFinal.data.communicationUrl) '취소 후 인원 / 링크 차단'
Write-Output "PASS: 작성, 상세, 수정, 참여 연동, 핵심 조건 잠금, 중복 차단, 목록, 취소 후 권한. postId=$taskId"
