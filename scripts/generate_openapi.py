"""Build the reviewed OpenAPI snapshot and check current Java contracts.

Run: python scripts/generate_openapi.py.
Controller signatures, record fields and enum values are checked against source.
Service rules and descriptions below are manually reviewed metadata.
"""

import json
import re
from copy import deepcopy
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "openapi.json"


def r(name):
    return {"$ref": f"#/components/schemas/{name}"}


def obj(properties, required=None, additional=None, desc=None):
    value = {"type": "object", "properties": properties}
    if required:
        value["required"] = required
    if additional is not None:
        value["additionalProperties"] = additional
    if desc:
        value["description"] = desc
    return value


def arr(items):
    return {"type": "array", "items": items}


def nullable(schema):
    value = deepcopy(schema)
    # OpenAPI 3.0 Reference Objects ignore siblings, including nullable.
    if "$ref" in value:
        value = deepcopy(schemas[value["$ref"].rsplit("/", 1)[1]])
    value["nullable"] = True
    if "enum" in value and None not in value["enum"]:
        value["enum"].append(None)
    return value


def dt():
    return {"type": "string", "format": "date-time"}


def integer(minimum=None, maximum=None, fmt="int64"):
    value = {"type": "integer", "format": fmt}
    if minimum is not None:
        value["minimum"] = minimum
    if maximum is not None:
        value["maximum"] = maximum
    return value


def string(minimum=None, maximum=None, pattern=None, fmt=None):
    value = {"type": "string"}
    if minimum is not None:
        value["minLength"] = minimum
    if maximum is not None:
        value["maxLength"] = maximum
    if pattern is not None:
        value["pattern"] = pattern
    if fmt is not None:
        value["format"] = fmt
    return value


def enum(values, typ="string"):
    return {"type": typ, "enum": values}


def data_response(data=None, description="成功"):
    if data is None:
        name = "ApiResponseVoid"
        data = {"type": "object", "nullable": True, "enum": [None]}
    elif "$ref" in data:
        name = "ApiResponse" + data["$ref"].rsplit("/", 1)[1]
    elif data.get("type") == "array":
        name = "ApiResponseList" + data["items"]["$ref"].rsplit("/", 1)[1]
    else:
        raise ValueError(f"Unhandled response: {data}")
    schema = obj({
        "code": {"type": "integer", "format": "int32", "enum": [0], "example": 0},
        "message": {"type": "string", "enum": ["ok"], "example": "ok"},
        "data": data,
    }, required=["code", "message", "data"])
    schemas[name] = schema
    return {"description": description, "content": {"application/json": {"schema": r(name)}}}


def error_response(description="业务错误"):
    return {"description": description, "content": {"application/json": {"schema": r("ErrorResponse")}}}


def body(schema, required=True, content_type="application/json"):
    return {"required": required, "content": {content_type: {"schema": schema}}}


def param(name, location, schema, required=False, description=None):
    value = {"name": name, "in": location, "required": required, "schema": schema}
    if description:
        value["description"] = description
    return value


def page(item):
    name = "PageResult" + item["$ref"].rsplit("/", 1)[1]
    schemas[name] = obj({
        "items": arr(item),
        "total": integer(0),
        "page": integer(1),
        "size": integer(1),
        "pages": integer(0),
    }, required=["items", "total", "page", "size", "pages"])
    return r(name)


def standard_errors(include_rate=True):
    result = {
        "400": error_response("请求体格式错误或参数校验失败"),
        "401": error_response("未认证、Token 缺失或 Token 无效"),
        "403": error_response("权限不足"),
        "404": error_response("资源不存在"),
        "409": error_response("资源状态冲突"),
        "500": error_response("服务器内部错误"),
    }
    if include_rate:
        result["429"] = error_response("请求过于频繁")
    return result


schemas = {}
schemas["ErrorResponse"] = obj({
    "code": integer(fmt="int32"), "message": string(),
    "data": {"type": "object", "nullable": True, "enum": [None], "example": None},
}, required=["code", "message", "data"])

schemas["ApiResponse"] = obj({
    "code": {"type": "integer"},
    "message": {"type": "string"},
    "data": {"nullable": True},
}, required=["code", "message", "data"])
schemas["UserRole"] = enum(["STUDENT", "ADMIN"])
schemas["UserSummaryDto"] = obj({
    "id": integer(), "username": string(), "displayName": nullable(string()),
    "role": r("UserRole"),
}, required=["id", "username", "role"])
schemas["AuthTokenResponse"] = obj({
    "tokenType": enum(["Bearer"]), "accessToken": string(), "expiresIn": integer(0),
    "user": r("UserSummaryDto"),
}, required=["tokenType", "accessToken", "expiresIn", "user"])
schemas["RegisterRequest"] = obj({
    "username": string(3, 32, r"^[A-Za-z0-9_-]+$"),
    "password": string(8, 72), "displayName": nullable(string(1, 32)),
}, required=["username", "password"])
schemas["LoginRequest"] = obj({
    "username": string(1), "password": string(1, 72),
}, required=["username", "password"])

schemas["Degree"] = enum(["UNDERGRADUATE", "POSTGRADUATE", "DOCTORAL", "GENERAL"])
schemas["LifecycleStage"] = enum(["NEW_STUDENT", "ENROLLED", "GRADUATING", "ALUMNI", "UNKNOWN"])
schemas["CourseRecordDto"] = obj({
    "courseCode": nullable(string(maximum=64)), "courseName": nullable(string(maximum=128)),
    "term": nullable(string(maximum=64)),
})
schemas["CourseRecordInput"] = schemas["CourseRecordDto"]
schemas["UpdateProfileRequest"] = obj({
    "major": nullable(string(maximum=64)), "grade": nullable(integer(1000, 9999)),
    "campus": nullable(string(maximum=64)), "degree": nullable(r("Degree")),
    "college": nullable(string(maximum=64)), "lifecycleStage": nullable(r("LifecycleStage")),
    "courseRecords": nullable(arr(r("CourseRecordDto"))),
})
schemas["UserProfileDto"] = obj({
    "userId": integer(), "major": nullable(string()), "grade": nullable(integer()),
    "campus": nullable(string()), "degree": nullable(r("Degree")), "college": nullable(string()),
    "lifecycleStage": nullable(r("LifecycleStage")), "courseRecords": arr(r("CourseRecordDto")),
    "updatedAt": nullable(dt()),
}, required=["userId", "courseRecords"])

schemas["IntentType"] = enum(["OFFICIAL", "EXPERIENTIAL", "MIXED", "UNKNOWN"])
schemas["IntentDto"] = obj({"type": r("IntentType"), "confidence": {"type": "number", "format": "double", "minimum": 0, "maximum": 1}}, required=["type", "confidence"])
schemas["SourceType"] = enum(["OFFICIAL", "UGC"])
schemas["CitationReferenceType"] = enum(["EXTERNAL", "INTERNAL", "UGC"])
source_base = {
    "chunkId": integer(), "sourceType": r("SourceType"), "sourceOrg": nullable(string(maximum=128)),
    "publishedAt": nullable(dt()), "relevanceScore": nullable({"type": "number", "format": "double", "minimum": 0, "maximum": 1}),
    "quote": nullable(string(maximum=500)),
}
schemas["SourceCitationDto"] = {
    "oneOf": [r("ExternalSourceCitation"), r("InternalSourceCitation"), r("UgcSourceCitation")]
}
schemas["ExternalSourceCitation"] = obj({
    **source_base, "sourceType": enum(["OFFICIAL"]), "title": string(1, 512), "sourceUrl": string(1, 4096, fmt="uri-http"),
    "referenceType": enum(["EXTERNAL"]), "documentId": {"type": "integer", "nullable": True, "example": None},
    "externalSourceUrl": {"type": "string", "nullable": True, "example": None},
}, required=["chunkId", "sourceType", "title", "sourceUrl", "referenceType"])
schemas["InternalSourceCitation"] = obj({
    **source_base, "sourceType": enum(["OFFICIAL"]), "title": string(1, 512), "sourceUrl": {"type": "string", "nullable": True, "example": None},
    "referenceType": enum(["INTERNAL"]), "documentId": integer(1), "externalSourceUrl": nullable(string(maximum=4096, fmt="uri-http")),
}, required=["chunkId", "sourceType", "title", "referenceType", "documentId"])
schemas["UgcSourceCitation"] = obj({
    **source_base, "sourceType": enum(["UGC"]), "title": nullable(string(maximum=512)), "sourceUrl": nullable(string(maximum=4096)),
    "referenceType": enum(["UGC"]), "documentId": {"type": "integer", "nullable": True, "example": None},
    "externalSourceUrl": {"type": "string", "nullable": True, "example": None},
}, required=["chunkId", "sourceType", "referenceType"])
schemas["AnswerStatus"] = enum(["ANSWERED", "NO_EVIDENCE", "NEED_PROFILE", "FAILED"])
schemas["ChatStreamRequest"] = obj({
    "sessionId": nullable(integer(1)), "question": string(1, 1000),
    "useMemory": nullable({"type": "boolean", "default": True}), "maxSources": nullable(integer(1, 20, fmt="int32")),
}, required=["question"])
schemas["ChatStreamMetaDto"] = obj({"sessionId": integer(), "assistantMessageId": integer(), "intent": r("IntentDto")}, required=["sessionId", "assistantMessageId", "intent"])
schemas["ChatDeltaDto"] = obj({"content": string()}, required=["content"])
schemas["ChatDoneDto"] = obj({"sessionId": integer(), "assistantMessageId": integer(), "answerStatus": r("AnswerStatus"), "answer": string(), "sources": arr(r("SourceCitationDto"))}, required=["sessionId", "assistantMessageId", "answerStatus", "answer", "sources"])
schemas["ChatMessageRole"] = enum(["USER", "ASSISTANT"])
schemas["ChatMessageDto"] = obj({"id": integer(), "role": r("ChatMessageRole"), "content": string(1), "sources": arr(r("SourceCitationDto")), "createdAt": dt()}, required=["id", "role", "content", "sources", "createdAt"])
schemas["ChatSessionSummaryDto"] = obj({"id": integer(), "title": nullable(string()), "createdAt": dt(), "updatedAt": dt(), "lastMessagePreview": nullable(string())}, required=["id", "createdAt", "updatedAt"])
schemas["ChatSessionDetailDto"] = obj({"id": integer(), "title": nullable(string()), "messages": arr(r("ChatMessageDto")), "hasMore": {"type": "boolean"}, "createdAt": dt(), "updatedAt": dt()}, required=["id", "messages", "hasMore", "createdAt", "updatedAt"])

schemas["UgcStatus"] = enum(["PENDING", "APPROVED", "REJECTED", "REMOVED"])
schemas["UgcReportStatus"] = enum(["OPEN", "RESOLVED", "DISMISSED"])
schemas["ReviewDecision"] = enum(["APPROVE", "REJECT"])
schemas["CreateUgcPostRequest"] = obj({"title": string(3, 80), "content": string(20, 5000), "tags": nullable(arr(string(1, 20))), "relatedCourse": nullable(string(maximum=64)), "relatedLocation": nullable(string(maximum=64))}, required=["title", "content"])
schemas["ReportUgcRequest"] = obj({"reason": string(1, 500)}, required=["reason"])
schemas["ReviewUgcRequest"] = obj({"decision": r("ReviewDecision"), "reason": nullable(string(maximum=500))}, required=["decision"])
schemas["UgcPostDto"] = obj({"id": integer(), "authorId": integer(), "authorName": nullable(string()), "title": string(), "content": string(), "tags": arr(string()), "relatedCourse": nullable(string()), "relatedLocation": nullable(string()), "status": r("UgcStatus"), "likes": integer(0), "reports": integer(0), "rejectReason": nullable(string()), "createdAt": dt(), "reviewedAt": nullable(dt())}, required=["id", "authorId", "title", "content", "tags", "status", "likes", "reports", "createdAt"])
schemas["UgcReportDto"] = obj({"id": integer(), "postId": integer(), "reporterId": integer(), "reporterName": nullable(string()), "reason": string(), "status": r("UgcReportStatus"), "createdAt": dt()}, required=["id", "postId", "reporterId", "reason", "status", "createdAt"])

schemas["SourceConnectorType"] = enum(["LOCAL_UPLOAD", "SINGLE_URL", "SITE", "RSS", "SITEMAP"])
schemas["SourceScheduleType"] = enum(["MANUAL", "DAILY", "WEEKLY", "CRON"])
schemas["IngestRunStatus"] = enum(["RUNNING", "DEFERRED", "SUCCESS", "PARTIAL_SUCCESS", "FAILED"])
schemas["IngestRunItemStatus"] = enum(["QUEUED", "RUNNING", "SUCCEEDED", "SKIPPED", "FAILED"])
schemas["SourceRequest"] = obj({
    "name": string(1, 128), "type": string(1, 32), "connectorType": r("SourceConnectorType"), "baseUrl": nullable(string()),
    "enabled": nullable({"type": "boolean"}), "scheduleType": r("SourceScheduleType"), "scheduleZone": nullable(string(maximum=64)), "cronExpression": nullable(string(maximum=128)),
    "sameHostOnly": nullable({"type": "boolean"}), "maxDepth": nullable(integer()), "maxPages": nullable(integer()), "maxConcurrency": nullable(integer()),
    "includePatterns": nullable(arr(string())), "excludePatterns": nullable(arr(string())),
}, required=["name", "type", "connectorType", "scheduleType"])
schemas["SourceEnabledRequest"] = obj({"enabled": {"type": "boolean"}}, required=["enabled"])
schemas["SourcePreviewItem"] = obj({"sourceUrl": string(), "filename": string(), "mediaType": string(), "byteSize": integer(0)}, required=["sourceUrl", "filename", "mediaType", "byteSize"])
schemas["SourceDto"] = obj({
    "id": integer(), "name": string(), "type": string(), "connectorType": r("SourceConnectorType"), "baseUrl": nullable(string()), "enabled": {"type": "boolean"},
    "scheduleType": r("SourceScheduleType"), "scheduleZone": nullable(string()), "cronExpression": nullable(string()), "sameHostOnly": {"type": "boolean"},
    "maxDepth": integer(), "maxPages": integer(), "maxConcurrency": integer(), "includePatterns": arr(string()), "excludePatterns": arr(string()), "deletionStatus": nullable(string()),
    "nextRunAt": nullable(dt()), "lastRunAt": nullable(dt()), "latestRunStatus": nullable(r("IngestRunStatus")), "createdAt": dt(), "updatedAt": dt(),
}, required=["id", "name", "type", "connectorType", "enabled", "scheduleType", "sameHostOnly", "maxDepth", "maxPages", "maxConcurrency", "includePatterns", "excludePatterns", "createdAt", "updatedAt"])
schemas["ImportFileMetadata"] = obj({"clientId": string(), "displayTitle": string(), "externalSourceUrl": nullable(string()), "replaceDocumentId": nullable(integer()), "degree": nullable(string()), "college": nullable(string()), "campus": nullable(string()), "gradeRange": nullable(string())}, required=["clientId", "displayTitle"])
schemas["ImportMetadata"] = obj({"sourceId": nullable(integer()), "items": arr(r("ImportFileMetadata"))}, required=["items"])
schemas["RunIngestRequest"] = obj({"force": {"type": "boolean", "default": False}, "rebuildVectors": {"type": "boolean", "default": False}, "reason": nullable(string(maximum=200))})
schemas["IngestRunDto"] = obj({"id": integer(), "sourceId": integer(), "status": r("IngestRunStatus"), "added": integer(0), "updated": integer(0), "skipped": integer(0), "failed": integer(0), "errorDetail": nullable(string()), "startedAt": nullable(dt()), "finishedAt": nullable(dt())}, required=["id", "sourceId", "status", "added", "updated", "skipped", "failed"])
schemas["IngestRunItemDto"] = obj({"id": integer(), "runId": integer(), "itemKey": string(), "itemKind": string(), "status": r("IngestRunItemStatus"), "documentId": nullable(integer()), "retryCount": integer(0), "nextAttemptAt": nullable(dt()), "errorCode": nullable(string()), "errorSummary": nullable(string()), "checkpoint": obj({}, additional={}), "checkpointAttempt": integer(0), "createdAt": dt(), "updatedAt": dt()}, required=["id", "runId", "itemKey", "itemKind", "status", "retryCount", "checkpoint", "checkpointAttempt", "createdAt", "updatedAt"])
schemas["IngestRunDetailDto"] = obj({"id": integer(), "sourceId": integer(), "status": r("IngestRunStatus"), "triggerType": string(), "scheduledAt": nullable(dt()), "discovered": integer(0), "added": integer(0), "updated": integer(0), "skipped": integer(0), "failed": integer(0), "attempt": integer(0), "workerId": nullable(string()), "claimedAt": nullable(dt()), "heartbeatAt": nullable(dt()), "errorCode": nullable(string()), "errorDetail": nullable(string()), "startedAt": nullable(dt()), "finishedAt": nullable(dt()), "items": arr(r("IngestRunItemDto"))}, required=["id", "sourceId", "status", "triggerType", "discovered", "added", "updated", "skipped", "failed", "attempt", "items"])

schemas["DocumentReferenceType"] = enum(["EXTERNAL", "INTERNAL"])
schemas["DocumentStatus"] = enum(["PROCESSING", "ACTIVE", "FAILED", "DELETING", "DELETE_FAILED"])
schemas["DocumentDto"] = obj({"id": integer(), "sourceId": nullable(integer()), "title": string(), "sourceUrl": nullable(string()), "sourceOrg": nullable(string()), "publishedAt": nullable(dt()), "contentHash": string(), "referenceType": r("DocumentReferenceType"), "externalSourceUrl": nullable(string()), "originalFilename": nullable(string()), "documentStatus": r("DocumentStatus"), "downloadable": {"type": "boolean"}, "ingestedAt": nullable(dt()), "lastSeenAt": nullable(dt()), "lastCheckedAt": nullable(dt()), "degree": nullable(string()), "college": nullable(string()), "campus": nullable(string()), "gradeRange": nullable(string())}, required=["id", "title", "contentHash", "referenceType", "documentStatus", "downloadable"])

schemas["AdminJobStatus"] = enum(["QUEUED", "RUNNING", "SUCCEEDED", "PARTIAL_SUCCESS", "FAILED", "CANCELLED"])
schemas["AdminJobType"] = enum(["SOURCE_DELETE", "DOCUMENT_DELETE", "DOCUMENT_REINDEX", "CONSISTENCY_SCAN", "DATABASE_BACKUP", "EMBEDDING_REBUILD", "INDEX_CLEANUP"])
schemas["AdminJobItemDto"] = obj({"id": integer(), "jobId": integer(), "itemKey": string(), "itemType": string(), "targetType": string(), "targetId": nullable(integer()), "status": r("AdminJobStatus"), "retryCount": integer(0), "errorCode": nullable(string()), "errorSummary": nullable(string()), "checkpoint": obj({}, additional={}), "checkpointAttempt": integer(0), "createdAt": dt(), "updatedAt": dt()}, required=["id", "jobId", "itemKey", "itemType", "targetType", "status", "retryCount", "checkpoint", "checkpointAttempt", "createdAt", "updatedAt"])
schemas["AdminJobDto"] = obj({"id": integer(), "jobType": r("AdminJobType"), "status": r("AdminJobStatus"), "targetType": string(), "targetId": nullable(integer()), "requestedBy": nullable(integer()), "attempt": integer(0), "workerId": nullable(string()), "claimedAt": nullable(dt()), "heartbeatAt": nullable(dt()), "startedAt": nullable(dt()), "finishedAt": nullable(dt()), "errorCode": nullable(string()), "errorSummary": nullable(string()), "requestPayload": obj({}, additional={}), "createdAt": dt(), "updatedAt": dt(), "items": arr(r("AdminJobItemDto"))}, required=["id", "jobType", "status", "targetType", "attempt", "requestPayload", "createdAt", "updatedAt", "items"])

schemas["AdminUserDto"] = obj({"id": integer(), "username": string(), "displayName": nullable(string()), "role": r("UserRole"), "enabled": {"type": "boolean"}, "tokenVersion": integer(0), "createdAt": dt()}, required=["id", "username", "role", "enabled", "tokenVersion", "createdAt"])
schemas["RoleRequest"] = obj({"role": r("UserRole")}, required=["role"])
schemas["EnabledRequest"] = obj({"enabled": {"type": "boolean"}}, required=["enabled"])
schemas["ReportResolutionRequest"] = obj({"status": enum(["RESOLVED", "DISMISSED"])}, required=["status"])
schemas["CreateBackupRequest"] = obj({"retentionDays": nullable(integer(1, 3650))})
schemas["DatabaseBackupStatus"] = enum(["QUEUED", "RUNNING", "SUCCEEDED", "VALIDATED", "FAILED", "DELETE_FAILED"])
schemas["DatabaseBackupDto"] = obj({"id": integer(), "jobId": nullable(integer()), "status": r("DatabaseBackupStatus"), "format": string(), "sha256": nullable(string()), "sizeBytes": nullable(integer()), "requestedBy": nullable(integer()), "startedAt": nullable(dt()), "finishedAt": nullable(dt()), "retentionDays": integer(1), "expiresAt": nullable(dt()), "errorSummary": nullable(string()), "createdAt": dt(), "updatedAt": dt(), "downloadable": {"type": "boolean"}}, required=["id", "status", "format", "retentionDays", "downloadable", "createdAt", "updatedAt"])
schemas["ModelKind"] = enum(["CHAT", "EMBEDDING"])
schemas["ModelConfigurationStatus"] = enum(["ACTIVE", "PENDING", "FAILED", "RETIRED"])
model_request = {"kind": r("ModelKind"), "provider": string(1, 64), "model": string(1, 256), "baseUrl": nullable(string(maximum=2048)), "temperature": nullable({"type": "number", "format": "double", "minimum": 0, "maximum": 2}), "dimension": nullable(integer()), "apiKey": nullable(string(maximum=8192)), "keepExistingSecret": nullable({"type": "boolean"}), "existingConfigurationId": nullable(integer(1))}
schemas["ModelConfigurationRequest"] = obj(model_request, required=["kind", "provider", "model"])
schemas["ModelTestRequest"] = obj(model_request, required=["kind", "provider", "model"])
schemas["ModelConfigurationDto"] = obj({"id": integer(), "configurationKey": string(), "kind": r("ModelKind"), "provider": string(), "model": string(), "baseUrl": nullable(string()), "temperature": nullable({"type": "number", "format": "double"}), "dimension": nullable(integer()), "status": r("ModelConfigurationStatus"), "bootstrap": {"type": "boolean"}, "secretConfigured": {"type": "boolean"}, "maskedApiKey": nullable(string()), "available": {"type": "boolean"}, "restartRequired": {"type": "boolean"}, "failureReason": nullable(string()), "activatedAt": nullable(dt()), "createdAt": dt(), "updatedAt": dt()}, required=["id", "configurationKey", "kind", "provider", "model", "status", "bootstrap", "secretConfigured", "available", "restartRequired", "createdAt", "updatedAt"])
schemas["EmbeddingRebuildRequest"] = obj({"configurationId": integer(1)}, required=["configurationId"])
schemas["AdminAuditResult"] = enum(["SUCCEEDED", "FAILED"])
schemas["AdminAuditLogDto"] = obj({"id": integer(), "actorId": nullable(integer()), "action": string(), "targetType": string(), "targetId": nullable(integer()), "summary": obj({}, additional={}), "remoteAddress": nullable(string()), "result": r("AdminAuditResult"), "errorCode": nullable(integer()), "createdAt": dt()}, required=["id", "action", "targetType", "result", "createdAt"])
schemas["DatabaseOverviewDto"] = obj({"databaseVersion": string(), "pgvectorVersion": string(), "flywayVersion": string(), "databaseSizeBytes": integer(0), "indexSizeBytes": integer(0), "tableCounts": obj({}, additional={"type": "integer", "format": "int64"}), "activeConnections": integer(0, fmt="int32"), "storageAvailable": {"type": "boolean"}, "modelStatus": obj({"provider": string(), "model": string(), "configured": {"type": "boolean"}, "dimension": integer(0)}, required=["provider", "model", "configured", "dimension"]), "recentIngestionFailures": arr(obj({"id": integer(), "sourceId": integer(), "status": string(), "errorCode": nullable(string()), "finishedAt": nullable(dt())}, required=["id", "sourceId", "status"]))}, required=["databaseVersion", "pgvectorVersion", "flywayVersion", "databaseSizeBytes", "indexSizeBytes", "tableCounts", "activeConnections", "storageAvailable", "modelStatus", "recentIngestionFailures"])


components = {
    "schemas": schemas,
    "securitySchemes": {"bearerAuth": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"}},
}
paths = {}


def add(method, path, operation_id, summary, data=None, tags=None, params=None, request=None, public=False, admin=False, responses=None, description=None, content_type="application/json"):
    operation = {"operationId": operation_id, "summary": summary, "tags": tags or ["API"], "responses": {"200": data_response(data)}}
    if description:
        operation["description"] = description
    if params:
        operation["parameters"] = deepcopy(params)
    if request:
        operation["requestBody"] = request
    if not public:
        operation["security"] = [{"bearerAuth": []}]
    if admin:
        operation["description"] = (operation.get("description", "") + "\n\n需要 ADMIN 角色。\n").strip()
    if responses:
        operation["responses"].update(deepcopy(responses))
    else:
        operation["responses"].update(standard_errors())
    paths.setdefault(path, {})[method.lower()] = operation


page_params = [param("page", "query", integer(1, fmt="int32"), False, "页码，从 1 开始；默认 1"), param("size", "query", integer(1, 100, fmt="int32"), False, "每页数量；默认 20")]
id_param = lambda name: param(name, "path", integer(1), True)

add("POST", "/api/auth/register", "register", "注册学生账号", r("AuthTokenResponse"), ["认证"], request=body(r("RegisterRequest"), True), public=True)
add("POST", "/api/auth/login", "login", "登录并签发访问令牌", r("AuthTokenResponse"), ["认证"], request=body(r("LoginRequest"), True), public=True)
add("GET", "/api/auth/me", "getCurrentUser", "获取当前登录用户", r("UserSummaryDto"), ["认证"])
add("GET", "/api/profile", "getProfile", "查询当前用户画像", r("UserProfileDto"), ["画像"])
add("PUT", "/api/profile", "updateProfile", "更新当前用户画像", r("UserProfileDto"), ["画像"], request=body(r("UpdateProfileRequest")))
sse_response = {
    "200": {
        "description": "SSE 事件流；事件名为 meta、delta、source、done、error",
        "content": {
            "text/event-stream": {
                "schema": {"type": "string"},
            }
        },
    }
}
add("POST", "/api/chat/stream", "streamChat", "流式 RAG 问答", None, ["对话"], request=body(r("ChatStreamRequest")), responses=sse_response)
add("GET", "/api/chat/sessions", "listChatSessions", "分页查询当前用户会话", page(r("ChatSessionSummaryDto")), ["对话"], params=page_params)
add("GET", "/api/chat/sessions/{id}", "getChatSession", "查询会话详情", r("ChatSessionDetailDto"), ["对话"], params=[id_param("id")])
add("POST", "/api/ugc", "createUgcPost", "提交经验帖", r("UgcPostDto"), ["UGC"], request=body(r("CreateUgcPostRequest")))
add("GET", "/api/ugc", "listUgcPosts", "分页查询 UGC", page(r("UgcPostDto")), ["UGC"], params=page_params + [param("mine", "query", {"type": "boolean", "default": False}), param("status", "query", r("UgcStatus")), param("tag", "query", string())])
add("GET", "/api/ugc/{id}", "getUgcPost", "查询 UGC 详情", r("UgcPostDto"), ["UGC"], params=[id_param("id")])
add("POST", "/api/ugc/{id}/like", "likeUgcPost", "点赞 UGC", r("UgcPostDto"), ["UGC"], params=[id_param("id")])
add("POST", "/api/ugc/{id}/report", "reportUgcPost", "举报 UGC", None, ["UGC"], params=[id_param("id")], request=body(r("ReportUgcRequest")))
binary_response = {"description": "原始文件二进制流", "content": {"application/octet-stream": {"schema": {"type": "string", "format": "binary"}}}}
add("GET", "/api/documents/{documentId}/download", "downloadDocumentContent", "下载受保护的内部文档原始文件", None, ["文档"], params=[id_param("documentId")], responses={"200": binary_response, "404": error_response("文档不存在"), "401": error_response("未认证或 Token 无效"), "403": error_response("无权访问")})

add("GET", "/api/admin/sources", "listSources", "分页查询数据源", page(r("SourceDto")), ["管理-数据源"], params=page_params, admin=True)
add("POST", "/api/admin/sources", "createSource", "创建数据源", r("SourceDto"), ["管理-数据源"], request=body(r("SourceRequest")), admin=True)
add("POST", "/api/admin/sources/test", "testSource", "测试数据源连接并预览", arr(r("SourcePreviewItem")), ["管理-数据源"], request=body(r("SourceRequest")), admin=True)
add("GET", "/api/admin/sources/{sourceId}", "getSource", "查询数据源详情", r("SourceDto"), ["管理-数据源"], params=[id_param("sourceId")], admin=True)
add("PUT", "/api/admin/sources/{sourceId}", "updateSource", "更新数据源", r("SourceDto"), ["管理-数据源"], params=[id_param("sourceId")], request=body(r("SourceRequest")), admin=True)
add("PATCH", "/api/admin/sources/{sourceId}/enabled", "setSourceEnabled", "启用或禁用数据源", r("SourceDto"), ["管理-数据源"], params=[id_param("sourceId")], request=body(r("SourceEnabledRequest")), admin=True)
add("POST", "/api/admin/sources/{sourceId}/preview", "previewSource", "预览数据源内容", arr(r("SourcePreviewItem")), ["管理-数据源"], params=[id_param("sourceId")], admin=True)
add("DELETE", "/api/admin/sources/{sourceId}", "deleteSource", "异步删除数据源", r("AdminJobDto"), ["管理-数据源"], params=[id_param("sourceId")], admin=True)

multipart = obj({"metadata": string(1), "files": arr({"type": "string", "format": "binary"})}, required=["metadata", "files"])
add("POST", "/api/admin/imports", "createImport", "上传并创建本地导入任务", r("IngestRunDetailDto"), ["管理-导入"], request=body(multipart, True, "multipart/form-data"), admin=True, description="metadata 为 ImportMetadata 的 JSON 字符串；files 可重复提交多个文件。单文件最多 20 MiB，批次最多 100 MiB。")
add("GET", "/api/admin/imports", "listImportRuns", "分页查询本地导入任务", page(r("IngestRunDto")), ["管理-导入"], params=page_params, admin=True)
add("GET", "/api/admin/imports/{runId}", "getImportRun", "查询本地导入任务详情", r("IngestRunDetailDto"), ["管理-导入"], params=[id_param("runId")], admin=True)
add("POST", "/api/admin/imports/{runId}/retry", "retryImportRun", "重试本地导入任务", r("IngestRunDetailDto"), ["管理-导入"], params=[id_param("runId")], admin=True)

doc_params = page_params + [param("sourceId", "query", integer(1)), param("status", "query", r("DocumentStatus")), param("title", "query", string()), param("referenceType", "query", r("DocumentReferenceType")), param("ingestedFrom", "query", dt()), param("ingestedTo", "query", dt())]
add("GET", "/api/admin/documents", "listDocuments", "分页查询文档", page(r("DocumentDto")), ["管理-文档"], params=doc_params, admin=True)
add("GET", "/api/admin/documents/{documentId}", "getDocument", "查询文档详情", r("DocumentDto"), ["管理-文档"], params=[id_param("documentId")], admin=True)
add("POST", "/api/admin/documents/{documentId}/reindex", "reindexDocument", "重新建立文档索引", r("AdminJobDto"), ["管理-文档"], params=[id_param("documentId")], admin=True)
add("DELETE", "/api/admin/documents/{documentId}", "deleteDocument", "异步删除文档", r("AdminJobDto"), ["管理-文档"], params=[id_param("documentId")], admin=True)

add("POST", "/api/admin/ingest/{sourceId}/run", "runSourceIngest", "手动触发数据源摄取", r("IngestRunDto"), ["管理-摄取"], params=[id_param("sourceId")], request=body(r("RunIngestRequest")), admin=True)
add("GET", "/api/admin/ingest/runs", "listIngestRuns", "分页查询摄取运行记录", page(r("IngestRunDto")), ["管理-摄取"], params=page_params + [param("sourceId", "query", integer(1)), param("status", "query", r("IngestRunStatus"))], admin=True)
add("GET", "/api/admin/ingest/runs/{runId}", "getIngestRun", "查询摄取运行详情", r("IngestRunDetailDto"), ["管理-摄取"], params=[id_param("runId")], admin=True)
add("POST", "/api/admin/ingest/runs/{runId}/retry", "retryIngestRun", "重试失败的摄取运行", r("IngestRunDto"), ["管理-摄取"], params=[id_param("runId")], admin=True)
add("GET", "/api/admin/jobs/{jobId}", "getAdminJob", "查询管理任务详情", r("AdminJobDto"), ["管理-任务"], params=[id_param("jobId")], admin=True)
add("POST", "/api/admin/jobs/{jobId}/retry", "retryAdminJob", "重试管理任务", r("AdminJobDto"), ["管理-任务"], params=[id_param("jobId")], admin=True)

add("POST", "/api/admin/ugc/{id}/review", "reviewUgcPost", "审核 UGC", r("UgcPostDto"), ["管理-UGC"], params=[id_param("id")], request=body(r("ReviewUgcRequest")), admin=True)
add("GET", "/api/admin/ugc/{postId}/reports", "listUgcReports", "分页查询 UGC 举报", page(r("UgcReportDto")), ["管理-UGC"], params=page_params + [id_param("postId")], admin=True)
add("POST", "/api/admin/ugc/{id}/remove", "removeUgcPost", "移除 UGC", r("UgcPostDto"), ["管理-UGC"], params=[id_param("id")], admin=True)
add("PATCH", "/api/admin/ugc/{postId}/reports/{reportId}", "resolveUgcReport", "处理 UGC 举报", r("UgcReportDto"), ["管理-UGC"], params=[id_param("postId"), id_param("reportId")], request=body(r("ReportResolutionRequest")), admin=True)

add("GET", "/api/admin/users", "listAdminUsers", "分页查询用户", page(r("AdminUserDto")), ["管理-用户"], params=page_params, admin=True)
add("PATCH", "/api/admin/users/{userId}/role", "updateUserRole", "修改用户角色", r("AdminUserDto"), ["管理-用户"], params=[id_param("userId")], request=body(r("RoleRequest")), admin=True)
add("PATCH", "/api/admin/users/{userId}/enabled", "setUserEnabled", "启用或禁用用户", r("AdminUserDto"), ["管理-用户"], params=[id_param("userId")], request=body(r("EnabledRequest")), admin=True)
add("POST", "/api/admin/users/{userId}/invalidate-tokens", "invalidateUserTokens", "使用户旧令牌失效", r("AdminUserDto"), ["管理-用户"], params=[id_param("userId")], admin=True)

add("GET", "/api/admin/overview", "getAdminOverview", "查询管理台概览", r("DatabaseOverviewDto"), ["管理-系统"], admin=True)
add("GET", "/api/admin/database/overview", "getDatabaseOverview", "查询数据库概览", r("DatabaseOverviewDto"), ["管理-系统"], admin=True)
add("POST", "/api/admin/database/consistency-scans", "startConsistencyScan", "启动一致性扫描", r("AdminJobDto"), ["管理-系统"], admin=True)
add("GET", "/api/admin/database/consistency-scans/{jobId}", "getConsistencyScan", "查询一致性扫描任务", r("AdminJobDto"), ["管理-系统"], params=[id_param("jobId")], admin=True)
add("GET", "/api/admin/backups", "listBackups", "分页查询数据库备份", page(r("DatabaseBackupDto")), ["管理-备份"], params=page_params, admin=True)
add("POST", "/api/admin/backups", "createBackup", "创建数据库备份", r("DatabaseBackupDto"), ["管理-备份"], request=body(r("CreateBackupRequest"), False), admin=True)
add("POST", "/api/admin/backups/{backupId}/validate", "validateBackup", "校验数据库备份", r("DatabaseBackupDto"), ["管理-备份"], params=[id_param("backupId")], admin=True)
add("GET", "/api/admin/backups/{backupId}/download", "downloadBackup", "下载数据库备份", None, ["管理-备份"], params=[id_param("backupId")], admin=True, responses={"200": binary_response, "401": error_response(), "403": error_response(), "404": error_response()})
add("DELETE", "/api/admin/backups/{backupId}", "deleteBackup", "删除数据库备份", None, ["管理-备份"], params=[id_param("backupId")], admin=True)

add("GET", "/api/admin/models", "listModelConfigurations", "查询模型配置", arr(r("ModelConfigurationDto")), ["管理-模型"], admin=True)
add("POST", "/api/admin/models/test", "testModelConfiguration", "测试模型配置", None, ["管理-模型"], request=body(r("ModelTestRequest")), admin=True)
add("POST", "/api/admin/models/configurations", "createModelConfiguration", "保存模型配置", r("ModelConfigurationDto"), ["管理-模型"], request=body(r("ModelConfigurationRequest")), admin=True)
add("GET", "/api/admin/indexes/rebuilds", "listEmbeddingRebuilds", "查询向量重建任务", arr(r("AdminJobDto")), ["管理-向量"], admin=True)
add("POST", "/api/admin/indexes/rebuilds", "startEmbeddingRebuild", "启动向量重建", r("AdminJobDto"), ["管理-向量"], request=body(r("EmbeddingRebuildRequest")), admin=True)
add("POST", "/api/admin/indexes/{configurationId}/activate", "activateEmbeddingRebuild", "激活向量配置", None, ["管理-向量"], params=[id_param("configurationId")], admin=True)
add("DELETE", "/api/admin/indexes/{configurationId}", "discardEmbeddingRebuild", "放弃向量配置候选", r("AdminJobDto"), ["管理-向量"], params=[id_param("configurationId")], admin=True)

audit_params = page_params + [param("actorId", "query", integer(1)), param("action", "query", string()), param("targetType", "query", string()), param("targetId", "query", integer(1)), param("result", "query", r("AdminAuditResult")), param("createdFrom", "query", dt()), param("createdTo", "query", dt())]
add("GET", "/api/admin/audit-logs", "listAuditLogs", "分页查询审计日志", page(r("AdminAuditLogDto")), ["管理-审计"], params=audit_params, admin=True)

spec = {
    "openapi": "3.0.3",
    "info": {"title": "CollegeAssistant API", "version": "2026.10", "description": "根据当前 Spring Boot 控制器、DTO、校验注解及前端调用生成。基础路径已包含在 paths 中，可直接导入 Apifox。"},
    "servers": [{"url": "http://localhost:8080", "description": "本地后端；可在 Apifox 环境中修改"}],
    "tags": [{"name": name} for name in ["认证", "画像", "对话", "UGC", "文档", "管理-数据源", "管理-导入", "管理-文档", "管理-摄取", "管理-任务", "管理-UGC", "管理-用户", "管理-系统", "管理-备份", "管理-模型", "管理-向量", "管理-审计"]],
    "paths": paths,
    "components": components,
}


def enrich_contracts():
    """Apply service validation and protocol details found in current code."""
    from validate_openapi import contracts

    records, enums, routes = contracts()
    for name, values in enums.items():
        if name in schemas:
            assert schemas[name]["enum"] == values, f"Review changed enum: {name}"
    for name, record in records.items():
        if name not in schemas or "properties" not in schemas[name] or name == "ApiResponse":
            continue
        properties = schemas[name]["properties"]
        assert set(properties) == set(record["fields"]), f"Review changed record: {name}"
        schemas[name]["x-java-source"] = record["path"]
        for field, declaration in record["fields"].items():
            typ = declaration["type"]
            if typ in ("int", "Integer", "long", "Long"):
                properties[field]["format"] = "int32" if typ in ("int", "Integer") else "int64"
        # Jackson writes every record component, including explicit null values.
        if name.endswith("Dto") or name in ("AuthTokenResponse", "SourcePreviewItem", "CourseRecordDto"):
            schemas[name]["required"] = list(properties)
    for name, schema in schemas.items():
        if name.startswith("PageResult"):
            for field in ("page", "size", "pages"):
                schema["properties"][field]["format"] = "int32"
    for path, operations in paths.items():
        for method, operation in operations.items():
            route = routes[(method, path)]
            operation["x-java-source"] = route["path"]
            if path in ("/api/auth/login", "/api/auth/register"):
                operation["security"] = []
            for parameter in operation.get("parameters", []):
                declaration = route["parameters"][(parameter["in"], parameter["name"])]
                default = re.search(r'defaultValue\s*=\s*"([^"]+)"', declaration["raw"])
                if default:
                    value = default[1]
                    typ = declaration["type"]
                    value = int(value) if typ == "int" else value == "true" if typ == "boolean" else value
                    parameter["schema"]["default"] = value
                # Some older UGC id routes intentionally do not validate positivity.
                if parameter["in"] == "path" and "@Positive" not in declaration["raw"] and path != "/api/chat/sessions/{id}":
                    parameter["schema"].pop("minimum", None)
            operation["responses"].pop("429", None)
            if path in ("/api/auth/login", "/api/auth/register"):
                operation["responses"]["429"] = error_response("认证限流：42900")
            for response in operation["responses"].values():
                schema = response.get("content", {}).get("application/json", {}).get("schema")
                if schema == r("ErrorResponse"):
                    response["content"]["application/json"]["example"] = {
                        "code": 40001, "message": "参数校验失败", "data": None,
                    }

    for name in ("RegisterRequest", "LoginRequest"):
        p = schemas[name]["properties"]["password"]
        p.update({"format": "password", "writeOnly": True, "x-max-utf8-bytes": 72,
                  "description": "必填且非空白；最多 72 个 UTF-8 字节（不是 72 个汉字）。注册另要求至少 8 个 Java 字符。"})
    for name in ("RegisterRequest", "LoginRequest", "SourceRequest", "ModelConfigurationRequest", "ModelTestRequest", "CreateUgcPostRequest", "ReportUgcRequest"):
        for field in schemas[name].get("required", []):
            prop = schemas[name]["properties"][field]
            if prop.get("type") == "string":
                prop.setdefault("pattern", r"[\s\S]*\S[\s\S]*")

    schemas["CourseRecordInput"] = deepcopy(schemas["CourseRecordDto"])
    schemas["CourseRecordInput"].pop("required", None)
    schemas["CourseRecordInput"].update({
        "description": "课程代码与课程名称至少一项非空白；列表元素不能为 null。",
        "anyOf": [
            {"required": [field], "properties": {field: string(1, 64 if field == "courseCode" else 128, r"[\s\S]*\S[\s\S]*")}}
            for field in ("courseCode", "courseName")
        ],
    })
    schemas["UpdateProfileRequest"]["properties"]["courseRecords"].update({"maxItems": 100, "items": r("CourseRecordInput")})
    schemas["UpdateProfileRequest"]["description"] = "覆盖更新当前用户画像；未传的字段清空，courseRecords 未传或 null 时存为空列表。"
    schemas["CreateUgcPostRequest"]["properties"]["tags"].update({"maxItems": 10})
    schemas["CreateUgcPostRequest"]["properties"]["tags"]["items"]["pattern"] = r"[\s\S]*\S[\s\S]*"
    schemas["ReviewUgcRequest"]["description"] = "decision=REJECT 时 reason 必填且非空白，最多 500 个 Java 字符；APPROVE 时不保留 reason。"
    schemas["ReviewUgcRequest"]["oneOf"] = [
        {"properties": {"decision": enum(["APPROVE"]) }},
        {"required": ["reason"], "properties": {"decision": enum(["REJECT"]), "reason": string(1, 500, r"[\s\S]*\S[\s\S]*")}},
    ]
    schemas["ReportUgcRequest"]["properties"]["reason"]["description"] = "原始输入非空白且最多 500 个 Java 字符；保存前去除首尾空白。"
    schemas["RunIngestRequest"]["properties"]["force"]["nullable"] = True
    schemas["RunIngestRequest"]["properties"]["rebuildVectors"]["nullable"] = True
    schemas["ChatStreamRequest"]["properties"]["maxSources"]["default"] = 8
    question = schemas["ChatStreamRequest"]["properties"]["question"]
    question.pop("maxLength")
    question.update({"pattern": r"[\s\S]*\S[\s\S]*", "x-max-normalized-code-points": 1000,
                     "description": "必填且非空白；Java strip 去除首尾空白后最多 1000 个 Unicode 码点。"})
    schemas["ChatMessageDto"]["properties"]["sources"]["maxItems"] = 20
    schemas["ChatDoneDto"]["properties"]["sources"]["maxItems"] = 20
    schemas["ChatSessionSummaryDto"]["properties"]["title"]["maxLength"] = 200
    schemas["ChatSessionSummaryDto"]["properties"]["lastMessagePreview"]["maxLength"] = 120

    citation_fields = list(schemas["ExternalSourceCitation"]["properties"])
    for name in ("ExternalSourceCitation", "InternalSourceCitation", "UgcSourceCitation"):
        props = schemas[name]["properties"]
        schemas[name]["required"] = citation_fields
        props["chunkId"]["minimum"] = 1
        for field in ("sourceUrl", "externalSourceUrl"):
            if props[field].get("format") == "uri-http":
                props[field]["format"] = "uri"
                props[field]["description"] = "绝对 HTTP(S) 地址，禁止 userinfo。"
        for field in ("documentId", "externalSourceUrl"):
            if name != "InternalSourceCitation":
                props[field]["enum"] = [None]
        if name == "InternalSourceCitation":
            props["sourceUrl"]["enum"] = [None]
    schemas["SourceCitationDto"]["discriminator"] = {
        "propertyName": "referenceType",
        "mapping": {"EXTERNAL": "#/components/schemas/ExternalSourceCitation", "INTERNAL": "#/components/schemas/InternalSourceCitation", "UGC": "#/components/schemas/UgcSourceCitation"},
    }

    source = schemas["SourceRequest"]["properties"]
    for field, default, minimum, maximum in (("maxDepth", 3, 0, 5), ("maxPages", 500, 1, 1000), ("maxConcurrency", 4, 1, 8)):
        source[field].update({"default": default, "minimum": minimum, "maximum": maximum})
    source["enabled"]["default"] = True
    source["sameHostOnly"]["default"] = True
    source["scheduleZone"]["default"] = "Asia/Shanghai"
    source["scheduleZone"]["description"] = "Java ZoneId 时区；未传、null 或空白时使用 Asia/Shanghai。"
    source["cronExpression"]["description"] = "DAILY/WEEKLY/CRON 均须提供 Spring CronExpression 接受的表达式，通常为六字段；相邻执行时间至少间隔 15 分钟。MANUAL 不接受非空 cronExpression。"
    source["baseUrl"]["description"] = "远程连接器必填绝对 HTTP(S) URL且禁止 userinfo；LOCAL_UPLOAD 必须为空。"
    for field in ("includePatterns", "excludePatterns"):
        source[field].update({"maxItems": 100, "default": [], "items": string(1, 256, r"[\s\S]*\S[\s\S]*")})
    schemas["SourceRequest"]["description"] = "LOCAL_UPLOAD 仅支持 MANUAL 调度。DAILY/WEEKLY/CRON 均必须提供合法 cronExpression；后端不会为 DAILY/WEEKLY 自动填充表达式。远程网络目标受部署级策略限制。"
    schemas["ImportMetadata"]["required"] = ["sourceId", "items"]
    schemas["ImportMetadata"]["properties"]["sourceId"] = integer(1)
    schemas["ImportMetadata"]["properties"]["items"].update({"minItems": 1, "maxItems": 20})
    schemas["ImportMetadata"]["description"] = "sourceId 必填并指向 LOCAL_UPLOAD 来源。items 与 files 数量一致，按数组顺序一一配对；clientId 唯一。"
    metadata = schemas["ImportFileMetadata"]
    metadata["required"] = ["clientId"]
    metadata["properties"]["clientId"] = string(1, 128, r"[\s\S]*\S[\s\S]*")
    metadata["properties"]["displayTitle"] = nullable(string(maximum=512))
    metadata["properties"]["displayTitle"]["description"] = "可选，空白时从文件名生成标题。"
    metadata["properties"]["replaceDocumentId"] = nullable(integer(1))
    for field, maximum in (("degree", 32), ("college", 64), ("campus", 64), ("gradeRange", 64), ("externalSourceUrl", 4096)):
        metadata["properties"][field]["maxLength"] = maximum
    metadata["properties"]["externalSourceUrl"]["description"] = "可选；非空白时必须为绝对 HTTP(S) URL。"
    multipart_body = paths["/api/admin/imports"]["post"]["requestBody"]["content"]["multipart/form-data"]
    multipart_body["schema"]["properties"]["files"].update({"minItems": 1, "maxItems": 20})
    multipart_body["schema"]["properties"]["metadata"].update({
        "description": "ImportMetadata 的 JSON 序列化字符串；作为普通表单文本字段提交。",
        "example": json.dumps({"sourceId": 1, "items": [{"clientId": "file-1", "displayTitle": "学生手册"}]}, ensure_ascii=False),
    })
    multipart_body["encoding"] = {"metadata": {"contentType": "text/plain"}, "files": {"contentType": "application/octet-stream"}}

    for name in ("ModelConfigurationRequest", "ModelTestRequest"):
        model = schemas[name]["properties"]
        model["provider"]["description"] = "忽略大小写。CHAT: ollama/openai/deepseek/zhipu/dashscope；EMBEDDING: ollama/openai/zhipu/dashscope。"
        model["temperature"].update({"default": 0.7, "description": "仅 CHAT 允许；默认 0.7，范围 0..2。EMBEDDING 必须省略或 null。"})
        model["dimension"].update({"minimum": 1536, "maximum": 1536, "description": "仅 EMBEDDING 允许，默认且必须为 1536；CHAT 必须省略或 null。"})
        model["apiKey"].update({"writeOnly": True, "format": "password", "description": "JSON 字符串。除 Ollama 外，新建配置必须提供密钥或选择保留旧密钥。"})
        model["keepExistingSecret"].update({"default": False, "description": "true 时不能同时提供新密钥，且必须提供可保留密钥的 existingConfigurationId。"})
        model["existingConfigurationId"]["description"] = "仅 keepExistingSecret=true 时允许；必须为可保留持久化密钥的现有配置。"
        model["baseUrl"]["description"] = "未传或空白时使用厂商默认地址；请求 origin 必须在服务端白名单内。"
    schemas["CreateBackupRequest"]["properties"]["retentionDays"]["description"] = "省略时采用部署配置 app.backup.default-retention-days，默认 7 天。"

    # Replace broad scaffold errors with the applicable response statuses.
    not_found = {operation["operationId"] for operations in paths.values() for operation in operations.values() if any(p["in"] == "path" for p in operation.get("parameters", []))}
    not_found.update({"listUgcReports", "createImport"})
    conflict = {
        "register", "likeUgcPost", "reportUgcPost", "createUgcPost", "reviewUgcPost", "removeUgcPost", "resolveUgcReport", "updateUserRole", "setUserEnabled", "invalidateUserTokens",
        "createSource", "updateSource", "setSourceEnabled", "testSource", "previewSource", "deleteSource", "createImport", "retryImportRun", "reindexDocument", "deleteDocument",
        "runSourceIngest", "retryIngestRun", "getConsistencyScan", "retryAdminJob", "createBackup", "validateBackup", "deleteBackup", "downloadBackup", "downloadDocumentContent",
        "createModelConfiguration", "startEmbeddingRebuild", "activateEmbeddingRebuild", "discardEmbeddingRebuild",
    }
    unavailable = {"createBackup", "validateBackup", "testModelConfiguration", "createModelConfiguration", "startEmbeddingRebuild", "activateEmbeddingRebuild"}
    codes = {"400": 40001, "401": 40101, "403": 40301, "404": 40400, "409": 40900, "429": 42900, "500": 50000, "503": 50302}
    messages = {"400": "参数校验失败", "401": "Token 缺失", "403": "需要管理员权限", "404": "资源不存在", "409": "资源状态冲突", "429": "请求过于频繁", "500": "服务器内部错误", "503": "能力不可用"}
    for path, operations in paths.items():
        for method, operation in operations.items():
            operation_id = operation["operationId"]
            responses = operation["responses"]
            responses.update({"400": error_response("参数校验失败（40001）或请求体格式错误（40002）"), "401": error_response("Token 缺失/过期/无效（40101/40102/40103）；登录失败 40100"), "500": error_response("未预期异常或模块内部错误")})
            if path.startswith("/api/admin/"):
                responses["403"] = error_response("需要管理员权限（40301）")
            else:
                responses.pop("403", None)
            if operation_id not in not_found:
                responses.pop("404", None)
            if operation_id in conflict:
                responses["409"] = error_response("状态冲突：参见错误码及接口说明")
            else:
                responses.pop("409", None)
            if operation_id in unavailable:
                responses["503"] = error_response("备份能力不可用（50301）或模型配置不可用（50302）")
            for status, response in responses.items():
                if status == "200":
                    continue
                code = 40100 if path == "/api/auth/login" and status == "401" else codes[status]
                if status == "503" and "Backup" in operation_id:
                    code = 50301
                message = messages[status]
                if operation_id == "login" and status == "401":
                    message = "用户名或密码错误"
                response["content"]["application/json"]["example"] = {"code": code, "message": message, "data": None}

    def describe(path, method, text):
        operation = paths[path][method]
        suffix = "\n\n需要 ADMIN 角色。" if path.startswith("/api/admin/") else ""
        operation["description"] = text + suffix

    describe("/api/auth/login", "post", "用户名、密码错误或账号禁用返回 HTTP 401 / code=40100。认证限流默认：IP 每分钟 30 次；同用户名+IP 五分钟最多 5 次失败。")
    describe("/api/auth/register", "post", "创建 STUDENT 账号并立即签发 Bearer JWT。重复用户名返回 40901；默认每个来源 IP 每小时最多 5 次注册请求。")
    describe("/api/auth/me", "get", "返回当前账号摘要；账号状态、角色或 tokenVersion 变化后，旧令牌失效。")
    describe("/api/chat/sessions/{id}", "get", "仅当前用户拥有的会话可读；默认返回最近 200 条已完成消息，超过限制时 hasMore=true。数量可由部署配置调整。")
    describe("/api/ugc", "get", "mine=true 查询本人帖子；mine=false 时学生始终只查询 APPROVED（传入其他 status 会被忽略），管理员可按 status 过滤全部作者。没有 sort 参数。")
    describe("/api/ugc/{id}/like", "post", "仅 APPROVED 内容可点赞。同一用户重复调用幂等成功，计数不会重复增加。维护窗口会返回冲突。")
    describe("/api/ugc/{id}/report", "post", "仅 APPROVED 内容可举报。同一用户重复调用幂等成功，保留首次原因。成功 data=null。")
    describe("/api/admin/imports", "post", "multipart 字段 metadata 是 ImportMetadata 的 JSON 文本，files 是重复文件字段。元数据和文件按顺序对应；1..20 个文件，单文件最多 20 MiB、批次最多 100 MiB。扩展名支持 pdf/doc/docx/ppt/pptx/xls/xlsx/html/htm/txt/md。来源必须为 LOCAL_UPLOAD。返回已创建任务，后续查询详情获取进度。")
    describe("/api/documents/{documentId}/download", "get", "登录用户可下载可用文档的原始文件；成功直接返回二进制流与 Content-Disposition。Content-Type 根据文件名推断；对象不存在返回 404，文档或来源不可用返回 409。失败仍使用 application/json 的 ApiResponse。")
    for path in ("/api/documents/{documentId}/download", "/api/admin/backups/{backupId}/download"):
        response = paths[path]["get"]["responses"]["200"]
        response["headers"] = {"Content-Disposition": {"description": "attachment 文件名，支持 UTF-8 filename*", "schema": string()}}
        if "backups" in path:
            response["description"] = "PostgreSQL custom format 备份二进制流，直接返回，不包 ApiResponse。"
            response["headers"]["Content-Length"] = {"description": "备份文件字节数", "schema": integer(0)}
        else:
            response["content"] = {"*/*": {"schema": {"type": "string", "format": "binary"}}}

    for path, method in (("/api/admin/sources/{sourceId}", "delete"), ("/api/admin/documents/{documentId}", "delete"), ("/api/admin/documents/{documentId}/reindex", "post")):
        describe(path, method, "HTTP 200 返回 AdminJobDto，表示异步任务已创建；调用 GET /api/admin/jobs/{jobId} 查看执行结果。")
    describe("/api/admin/ingest/{sourceId}/run", "post", "请求体必须提供（可为 {}）；force/rebuildVectors 默认 false。异步返回 IngestRunDto，使用详情接口查询进度；同一来源已有活动任务返回 40903。")
    describe("/api/admin/ugc/{id}/review", "post", "仅 PENDING 帖子可审核；REJECT 要求非空 reason。APPROVE 提交后异步触发分块和索引。重复审核返回 40902。")
    describe("/api/admin/ugc/{postId}/reports/{reportId}", "patch", "只允许 RESOLVED 或 DISMISSED；OPEN 是读取状态，不是合法处理目标。重复处理返回 409。")
    describe("/api/admin/users/{userId}/role", "patch", "角色变化时递增 tokenVersion，使旧令牌立即失效。禁止降级唯一启用管理员（40905）。")
    describe("/api/admin/users/{userId}/enabled", "patch", "启停状态变化使旧令牌失效；禁止禁用唯一启用管理员（40905）。")
    describe("/api/admin/backups", "post", "请求体可省略；retentionDays 默认来自部署配置。创建备份及 DATABASE_BACKUP 异步任务；并发备份 40907、容量不足 40908、备份能力不可用 50301。")
    describe("/api/admin/models/configurations", "post", "服务端验证并探测后保存为 PENDING；需要重启构建候选客户端。密钥不返回，响应只提供 maskedApiKey 与 secretConfigured。")
    describe("/api/admin/indexes/rebuilds", "post", "为 PENDING Embedding 配置创建重建任务；候选客户端须已在重启时构建。重建期间保持旧配置检索，相关内容写入进入维护窗口。")
    describe("/api/admin/indexes/{configurationId}/activate", "post", "完成候选清单完整性检查后激活 Embedding 配置。成功 HTTP 200 且 data=null。")
    describe("/api/admin/indexes/{configurationId}", "delete", "放弃待激活候选或清理退休配置，返回 INDEX_CLEANUP 任务。活动/bootstrap 配置不可删除；RUNNING 重建不能放弃；QUEUED 重建先取消。")

    sse = paths["/api/chat/stream"]["post"]
    describe("/api/chat/stream", "post", "发送 Accept: text/event-stream 与 JSON 请求体。未开始流的认证/校验错误返回 JSON 和对应 HTTP 状态；流开始后的错误发送 event:error，HTTP 状态保持 200。事件 data 是 JSON，直接使用 DTO，不包成功 ApiResponse。\n\n事件顺序通常为 meta → delta/source → done；error 为失败终态。meta=ChatStreamMetaDto；delta=ChatDeltaDto；source=SourceCitationDto；done=ChatDoneDto；error=ErrorResponse。")
    events = {
        "meta": {"sessionId": 12, "assistantMessageId": 88, "intent": {"type": "OFFICIAL", "confidence": 0.91}},
        "delta": {"content": "根据学校官方文件，"},
        "source": {"chunkId": 301, "sourceType": "OFFICIAL", "title": "学生手册", "sourceUrl": "https://example.edu/handbook", "sourceOrg": "教务处", "publishedAt": None, "relevanceScore": 0.87, "quote": "相关规定摘录", "referenceType": "EXTERNAL", "documentId": None, "externalSourceUrl": None},
        "done": {"sessionId": 12, "assistantMessageId": 88, "answerStatus": "ANSWERED", "answer": "根据学校官方文件，相关规定如下。", "sources": []},
        "error": {"code": 50010, "message": "模型服务调用失败", "data": None},
    }
    media = sse["responses"]["200"]["content"]["text/event-stream"]
    media["examples"] = {name: {"value": "event: " + name + "\ndata: " + json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n\n"} for name, payload in events.items()}
    sse["x-sse-events"] = {name: r(schema) for name, schema in {"meta": "ChatStreamMetaDto", "delta": "ChatDeltaDto", "source": "SourceCitationDto", "done": "ChatDoneDto", "error": "ErrorResponse"}.items()}

    error_codes = re.findall(r"public static final int (\w+) = (\d+);", (ROOT / "src/main/java/xyz/v185v/collegeassistant/common/ErrorCode.java").read_text(encoding="utf-8"))
    spec["x-business-error-codes"] = {name: int(code) for name, code in error_codes}
    schemas["ErrorResponse"]["properties"]["code"]["enum"] = [int(code) for name, code in error_codes if name != "OK"]
    schemas["ErrorResponse"]["description"] = "失败 JSON 响应，data 始终为 null；BusinessException 的 HTTP 状态取业务码前三位。"
    spec["info"]["description"] = "校园智答当前工作区接口快照（2026-10-11）。按 Spring MVC 控制器、DTO、Bean Validation、服务层规则与前端调用核对。共 61 个操作、52 个路径；路径已包含 /api，服务器地址不要重复附加 /api。成功 JSON 使用 ApiResponse；SSE 与两个下载接口返回原始内容。所有 /api/admin/** 需要 ADMIN。"


if __name__ == "__main__":
    from validate_openapi import validate_source

    enrich_contracts()
    checks = validate_source(spec)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(spec, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(checks, ensure_ascii=False))
    print(f"Wrote {OUT}")
