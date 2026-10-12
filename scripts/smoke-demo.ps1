param([string]$BaseUrl = 'http://127.0.0.1:8080', [long]$PostId = 1)
$ErrorActionPreference = 'Stop'
$taskSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$taskAuth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('student1:demo-pass'))
$taskHeaders = @{ Authorization = "Basic $taskAuth" }
function Call-Api([string]$Method, [string]$Path) {
    Invoke-RestMethod -Method $Method -Uri "$BaseUrl$Path" -WebSession $taskSession -Headers $taskHeaders
}
function Assert-That([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw "검증 실패: $Message" }
}
$taskCsrf = Call-Api GET '/api/csrf'
$taskHeaders[$taskCsrf.data.headerName] = $taskCsrf.data.token
$taskPath = "/api/posts/$PostId/participants"
$taskBefore = Call-Api GET "$taskPath/me"
if ($taskBefore.data.viewer.myParticipationStatus -eq 'JOINED') { $null = Call-Api DELETE $taskPath }
$taskJoin = Call-Api POST $taskPath
Assert-That ($taskJoin.data.participation.status -eq 'JOINED') '참여 상태'
$taskId = $taskJoin.data.participation.participationId
$taskMine = Call-Api GET "$taskPath/me"
Assert-That (!$taskMine.data.viewer.canJoin -and $taskMine.data.viewer.canCancel) '버튼 상태'
$taskMembers = Call-Api GET $taskPath
Assert-That ($taskMembers.data.currentMemberCount -eq (1 + @($taskMembers.data.participants).Count)) '작성자 포함 인원'
$taskCancel = Call-Api DELETE $taskPath
Assert-That ($taskCancel.data.participation.status -eq 'CANCELLED') '취소 상태'
try { $null = Call-Api GET $taskPath; throw '취소자가 명단을 조회했습니다.' }
catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 403) { throw }
}
$taskRejoin = Call-Api POST $taskPath
Assert-That ($taskRejoin.data.action -eq 'REJOIN') '재참여 action'
Assert-That ($taskRejoin.data.participation.participationId -eq $taskId) '재참여 ID 유지'
Assert-That ($null -eq $taskRejoin.data.participation.cancelledAt) '취소 시각 초기화'
$taskHistory = Call-Api GET '/api/users/me/participations?page=0&size=20&status=JOINED'
Assert-That (@($taskHistory.data.items | Where-Object participationId -eq $taskId).Count -eq 1) '본인 내역'
$null = Call-Api DELETE $taskPath
Write-Output "PASS: 참여, 상태, 명단, 취소, 명단 권한 차단, 재참여 ID 유지, 내역 조회. participationId=$taskId"
