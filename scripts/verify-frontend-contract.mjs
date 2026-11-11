/** Execute a local, pinned frontend source snapshot against the test-only Spring HTTP server. */
import assert from "node:assert/strict";
import { readFile, writeFile, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve, dirname, join, sep } from "node:path";
import { pathToFileURL } from "node:url";
import { stripTypeScriptTypes } from "node:module";

const [origin, inputRoot, referenceTime] = process.argv.slice(2);
const endpoint = new URL(origin);
assert.equal(endpoint.protocol, "http:");
assert.equal(endpoint.hostname, "127.0.0.1", "Only the local test fixture may be used");
assert.equal(endpoint.pathname, "/");
const sourceRoot = resolve(inputRoot);
const outputRoot = await mkdtemp(join(tmpdir(), "community-fe-contract-"));
const compiled = new Map();
const observations = [];
const checks = [];
let cookies = new Map();
const transport = globalThis.fetch;

// Preserve runtime frontend code; remove types and resolve its local TS aliases in a temporary directory.
async function compile(file) {
  file = resolve(file);
  assert.ok(file.startsWith(sourceRoot + sep), "Import must stay inside the supplied source snapshot");
  if (compiled.has(file)) return compiled.get(file);
  const target = join(outputRoot, `${compiled.size}.mjs`);
  compiled.set(file, target);
  let code = stripTypeScriptTypes(await readFile(file, "utf8"), { mode: "transform" });
  const imports = [...code.matchAll(/\bfrom\s*['"]([^'"]+)['"]/g)];
  for (const match of imports) {
    const specifier = match[1];
    assert.ok(specifier.startsWith("@/") || specifier.startsWith("."), `Unexpected dependency: ${specifier}`);
    const dependency = specifier.startsWith("@/")
      ? resolve(sourceRoot, "src", specifier.slice(2)) : resolve(dirname(file), specifier);
    const url = pathToFileURL(await compile(dependency.endsWith(".ts") ? dependency : dependency + ".ts")).href;
    code = code.replaceAll(`"${specifier}"`, JSON.stringify(url)).replaceAll(`'${specifier}'`, JSON.stringify(url));
  }
  await writeFile(target, code);
  return target;
}

globalThis.fetch = async (url, init = {}) => {
  assert.equal(new URL(url).origin, endpoint.origin, "Probe must not contact another origin");
  assert.equal(init.credentials, "include");
  const headers = new Headers(init.headers);
  assert.equal(headers.get("Accept"), "application/json");
  if (cookies.size) headers.set("Cookie", [...cookies].map(([k, v]) => `${k}=${v}`).join("; "));
  const response = await transport(url, { ...init, headers });
  for (const value of response.headers.getSetCookie()) {
    const pair = value.split(";")[0];
    const index = pair.indexOf("=");
    const name = pair.slice(0, index), content = pair.slice(index + 1);
    if (/max-age=0(?:;|$)/i.test(value)) cookies.delete(name);
    else cookies.set(name, content);
  }
  observations.push({ method: init.method ?? "GET", path: new URL(url).pathname, status: response.status });
  return response;
};

try {
  process.env.NEXT_PUBLIC_API_BASE_URL = endpoint.origin;
  const load = async (path) => import(pathToFileURL(await compile(join(sourceRoot, path))).href);
  const backend = await load("src/lib/backend.ts");
  const api = await load("src/lib/api.ts");
  const { postFormOperations: form } = await load("src/features/posts/form/postFormApi.ts");
  const { postListOperations: list } = await load("src/features/posts/postsApi.ts");
  const { toPostPage } = await load("src/features/posts/posts.ts");
  const { postDetailOperations: detail } = await load("src/features/posts/detail/postDetailApi.ts");
  const { toPostDetail } = await load("src/features/posts/detail/postDetail.ts");
  const { myHistoryOperations: history } = await load("src/features/my/myHistoryApi.ts");
  const check = (name) => checks.push(name);
  const login = async (name) => {
    cookies = new Map(); backend.resetCsrfToken();
    await backend.backendSend("POST", "/api/auth/local/login", { identifier: `frontend-${name}`, password: "contract-pass" });
    assert.ok(cookies.has("JSESSIONID"));
    backend.resetCsrfToken(); // The real login rotates the anonymous CSRF token.
  };
  await login("author");
  const id = await form.create({ type: "TAXI", title: "어댑터 택시", description: "화면의 description을 content로 전송",
    capacity: 3, referenceTime, departure: "석계역", destination: "삼육대학교", communicationUrl: "https://example.com/room" });
  assert.match(id, /^[1-9][0-9]*$/); check("taxi_create_with_csrf_and_string_id");
  const delivery = await form.create({ type: "DELIVERY", title: "어댑터 배달", description: "함께 주문",
    capacity: 10, referenceTime: new Date(Date.parse(referenceTime) + 60_000).toISOString(),
    restaurantName: "음식점", pickupLocation: "정문", orderGuide: null, communicationUrl: null });
  assert.notEqual(delivery, id); check("delivery_create_with_null_optional_fields");
  const page = toPostPage(await list.list({ type: "ALL", joinableOnly: false, page: 0 }));
  assert.deepEqual(page.content.map((post) => post.id), [id, delivery]); check("list_envelope_mapping_and_default_order");
  const owner = toPostDetail(await detail.load(id));
  assert.equal(owner.relation, "AUTHOR"); assert.deepEqual(owner.participantNames, ["author"]);
  assert.equal(owner.description, "화면의 description을 content로 전송"); check("detail_mapping_and_author_names");
  const editable = await form.load(id);
  assert.equal(editable.isAuthor, true); assert.equal(editable.otherJoinedCount, 0); check("edit_form_load");
  await form.update(id, { title: "수정된 제목", description: "수정된 본문", communicationUrl: null });
  assert.equal(toPostDetail(await detail.load(id)).title, "수정된 제목"); check("patch_omits_immutable_type");
  await assert.rejects(() => backend.backendSendData("POST", "/api/posts", { type: "TAXI", capacity: "3" }),
    (error) => error.status === 400 && backend.errorCode(error) === "INVALID_REQUEST"); check("invalid_json_type_is_rejected");
  await login("student");
  const outsider = toPostDetail(await detail.load(id));
  assert.equal(outsider.relation, "NONE"); assert.equal(outsider.communicationUrl, undefined);
  assert.equal(outsider.participantNames, undefined); check("outsider_privacy");
  await assert.rejects(() => form.update(id, { title: "권한 없음" }), (error) => error.kind === "forbidden"); check("non_author_patch_is_rejected");
  await detail.join(id);
  const first = (await backend.backendGet(`/api/posts/${id}/participants/me`)).data.participation;
  const joined = toPostDetail(await detail.load(id));
  assert.equal(joined.relation, "JOINED"); assert.equal(joined.currentMemberCount, 2);
  assert.deepEqual(joined.participantNames, ["author", "student"]); check("join_and_member_names");
  await assert.rejects(() => detail.join(id), (error) => error.kind === "conflict"); check("duplicate_join_is_rejected");
  await detail.leave(id);
  assert.equal(toPostDetail(await detail.load(id)).relation, "CANCELLED"); check("cancel_and_privacy_refresh");
  await detail.join(id);
  const rejoin = (await backend.backendGet(`/api/posts/${id}/participants/me`)).data.participation;
  assert.equal(rejoin.participationId, first.participationId); assert.equal(rejoin.createdAt, first.createdAt);
  assert.equal(rejoin.cancelledAt, null); check("rejoin_reuses_participation");
  const records = await history.list("participated");
  assert.equal(records.content[0].relation, "JOINED"); check("participation_history_adapter");
  await login("other"); await detail.join(id);
  const full = toPostDetail(await detail.load(id));
  assert.equal(full.status, "FULL"); assert.equal(full.currentMemberCount, 3); assert.equal(full.joinable, false);
  await assert.rejects(() => detail.join(id), (error) => error.kind === "conflict"); check("full_post_never_exceeds_capacity");
  await assert.rejects(() => api.apiFetch(`/api/posts/${id}/participants`, { method: "DELETE", headers: { "X-XSRF-TOKEN": "forged" } }),
    (error) => error.status === 403 && backend.errorCode(error) === "CSRF_INVALID"); check("forged_csrf_is_rejected");
  await assert.rejects(() => detail.load("999999999"), (error) => error.kind === "notFound"); check("missing_post_error_mapping");
  await login("author");
  await assert.rejects(() => form.update(id, { capacity: 2 }), (error) => error.kind === "conflict"); check("core_edit_after_join_is_rejected");
  assert.equal(toPostDetail(await detail.load(id)).currentMemberCount, 3);
  console.log(JSON.stringify({ passed: checks.length, checks, requests: observations.length }));
} finally {
  globalThis.fetch = transport;
  assert.ok(resolve(outputRoot).startsWith(resolve(tmpdir()) + sep));
  await rm(outputRoot, { recursive: true, force: true });
}
