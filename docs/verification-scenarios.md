# Verification Scenarios

This document defines the **expected behavior** of the User Management API for normal and edge-case input.
Each scenario is written so it can be checked manually (curl / Postman) and is backed by an automated test.

All error responses share one shape:

```json
{
  "timestamp": "2026-01-15T10:30:00.123Z",
  "status": 400,
  "error": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "path": "/api/users",
  "fieldErrors": [
    { "field": "email", "message": "Email must be a valid email address" }
  ]
}
```

`fieldErrors` appears only for body validation failures and is sorted by field name so the output is deterministic.
Clients should branch on `error` (a stable code), not on `message` (human-readable text).

Baseline valid body used below:

```json
{ "name": "Asha Verma", "email": "asha.verma@example.com", "age": 30, "status": "ACTIVE" }
```

---

## Summary

| #  | Scenario                       | Expected status | `error` value        |
|----|--------------------------------|-----------------|----------------------|
| 1  | Valid request                  | 201 / 200 / 204 | –                    |
| 2  | Missing required fields        | 400             | `VALIDATION_FAILED`  |
| 3  | Invalid email                  | 400             | `VALIDATION_FAILED`  |
| 4  | Duplicate email                | 409             | `DUPLICATE_EMAIL`    |
| 5  | Non-existent user              | 404             | `USER_NOT_FOUND`     |
| 6  | Negative ID                    | 400             | `INVALID_ID`         |
| 7  | Zero ID                        | 400             | `INVALID_ID`         |
| 8  | Age below minimum              | 400             | `VALIDATION_FAILED`  |
| 9  | Age above maximum              | 400             | `VALIDATION_FAILED`  |
| 10 | Invalid status                 | 400             | `VALIDATION_FAILED`  |
| 11 | Malformed JSON                 | 400             | `MALFORMED_REQUEST`  |
| 12 | Empty request body             | 400             | `MALFORMED_REQUEST`  |
| 13 | Non-numeric / overflowing ID   | 400             | `INVALID_ID`         |
| 14 | Server-managed field in body   | 400             | `MALFORMED_REQUEST`  |
| 15 | Wrong JSON type for age        | 400             | `MALFORMED_REQUEST`  |
| 16 | Update keeping own email       | 200             | –                    |
| 17 | Search by email                | 200 / 400 / 404 | see scenario         |
| 18 | Unexpected server error        | 500             | `INTERNAL_ERROR`     |

---

## 1. Valid request

**Input**

```http
POST /api/users
Content-Type: application/json

{ "name": "  Asha Verma ", "email": "Asha.Verma@Example.com", "age": 30, "status": "ACTIVE" }
```

**Expected HTTP status:** `201 Created`

**Expected behavior**
- Response body contains `id`, `name`, `email`, `age`, `status`, `createdAt`, `updatedAt`.
- `Location` header points to the new resource, e.g. `http://localhost:8080/api/users/1`.
- `name` is trimmed to `"Asha Verma"`; `email` is normalized to `"asha.verma@example.com"`.
- `createdAt` equals `updatedAt` on creation.
- Follow-ups: `GET /api/users/{id}` → 200, `PUT` with a valid body → 200 with new `updatedAt` and unchanged `createdAt`, `DELETE` → 204 with empty body.

**Why the case matters**
The happy path defines the contract every other scenario is compared against. Normalization matters
because without it `Asha@x.com` and `asha@x.com` could become two separate accounts.

**Tests:** `UserServiceTest.shouldCreateUserWhenRequestIsValid`, `UserControllerTest.shouldCreateUserWhenRequestIsValid`,
`UserApiIntegrationTest.shouldCreateReadUpdateSearchAndDeleteUser`

---

## 2. Missing required fields

**Input**

```http
POST /api/users
Content-Type: application/json

{}
```

**Expected HTTP status:** `400 Bad Request`

**Expected behavior**
- `error` = `VALIDATION_FAILED`.
- `fieldErrors` lists **all four** problems in one response (sorted): `age` "Age is required",
  `email` "Email is required", `name` "Name is required", `status` "Status is required".
- Nothing is written to the database.

**Why the case matters**
Returning every error at once saves the client from a fix-one-resubmit loop. It also proves that `null`
fields are caught, not just badly formatted ones; a null that reaches the database would surface as a 500.

**Tests:** `UserControllerTest.shouldReturnEveryMissingFieldWhenBodyIsEmptyObject`,
`UserRequestValidationTest.shouldRejectUserWhen*IsMissing`

---

## 3. Invalid email

**Input:** baseline body with `email` set to any of:
`"plainaddress"`, `"user@"`, `"@example.com"`, `"user@@example.com"`, `"user name@example.com"`, `"user@localhost"`

**Expected HTTP status:** `400 Bad Request`

**Expected behavior**
- `error` = `VALIDATION_FAILED`, field `email`, message "Email must be a valid email address".
- Blank email (`""`) is also rejected with "Email is required". Note that a blank value currently fails
  both the required and the format check, so two messages are returned for `email`.
- Emails longer than 254 characters are rejected with "Email must not exceed 254 characters".

**Why the case matters**
`user@localhost` is accepted by the standard `@Email` annotation. This API adds a pattern requiring a dot
in the domain, a deliberate decision for a public-facing service. Unit tests pin that decision so a future
refactor that drops the pattern is caught.

**Tests:** `UserRequestValidationTest.shouldRejectUserWhenEmailIsInvalid` (parameterized),
`UserControllerTest.shouldRejectUserWhenEmailIsInvalid`

---

## 4. Duplicate email

**Input**
1. Create a user with `asha.verma@example.com`.
2. `POST /api/users` again with `"email": "ASHA.VERMA@EXAMPLE.COM"`.

**Expected HTTP status:** `409 Conflict`

**Expected behavior**
- `error` = `DUPLICATE_EMAIL`, message "A user with email asha.verma@example.com already exists".
- The user count does not change.
- On update: changing your email to **another** user's email → 409, and the original record is unchanged.

**Why the case matters**
The duplicate check must be case-insensitive, otherwise the uniqueness rule is easy to bypass. 409 (not 400)
tells the client the request was well-formed but conflicts with current state. A database unique constraint
backs the service check, so a race between two concurrent creates still yields a 409 (`DATA_CONFLICT`) rather than duplicates.

**Tests:** `UserServiceTest.shouldRejectDuplicateEmail`, `UserServiceTest.shouldRejectUpdateWhenEmailBelongsToAnotherUser`,
`UserApiIntegrationTest.shouldRejectDuplicateEmailRegardlessOfCase`, `UserApiIntegrationTest.shouldRejectUpdateWhenEmailBelongsToAnotherUser`

---

## 5. Non-existent user

**Input:** `GET /api/users/987654` (also `PUT` and `DELETE` on the same id)

**Expected HTTP status:** `404 Not Found`

**Expected behavior**
- `error` = `USER_NOT_FOUND`, message "User with id 987654 was not found", `path` = `/api/users/987654`.
- `DELETE` on a missing id also returns 404 (not 204). This is a deliberate choice: the client learns the id was wrong.

**Why the case matters**
Distinguishes "valid id, no such record" (404) from "id not valid at all" (400). Returning 200 with an empty body,
or a 500 from an unhandled `Optional.get()`, are both common bugs in this exact spot.

**Tests:** `UserServiceTest.shouldThrowNotFoundWhenUserDoesNotExist`, `UserControllerTest.shouldReturnNotFoundWhenUserDoesNotExist`,
`UserApiIntegrationTest.shouldReturnNotFoundWhenUserDoesNotExist`, `UserApiIntegrationTest.shouldReturnNotFoundWhenDeletingNonExistentUser`

---

## 6. Negative ID

**Input:** `GET /api/users/-1`

**Expected HTTP status:** `400 Bad Request`

**Expected behavior**
- `error` = `INVALID_ID`, message "User id must be a positive number but was -1".
- The database is **not** queried.

**Why the case matters**
Generated ids are always positive, so a negative id is a client bug, not a missing record. Answering 404 would
hide that bug. Rejecting early also avoids a pointless database round-trip.

**Tests:** `UserServiceTest.shouldRejectNonPositiveUserIdWithoutQueryingDatabase`, `UserServiceTest.shouldRejectNegativeUserIdOnDelete`,
`UserApiIntegrationTest.shouldRejectNegativeUserId`

---

## 7. Zero ID

**Input:** `GET /api/users/0` or `DELETE /api/users/0`

**Expected HTTP status:** `400 Bad Request`

**Expected behavior:** same as scenario 6, `error` = `INVALID_ID`.

**Why the case matters**
`0` is the classic boundary between valid and invalid. It is also the default value of an uninitialized `long`
in client code, so it shows up in real traffic more often than negative numbers.

**Tests:** `UserServiceTest.shouldRejectNonPositiveUserIdWithoutQueryingDatabase` (includes 0), `UserApiIntegrationTest.shouldRejectZeroUserId`

---

## 8. Age below minimum

**Input:** baseline body with `"age": 17` (also tested: `0`, `-1`, `Integer.MIN_VALUE`)

**Expected HTTP status:** `400 Bad Request`

**Expected behavior:** `VALIDATION_FAILED`, field `age`, message "Age must be at least 18". `"age": 18` is **accepted** (inclusive bound).

**Why the case matters**
Off-by-one errors (`>` vs `>=`) live at exactly this boundary. Both sides of the boundary (17 rejected, 18 accepted) are tested.

**Tests:** `UserRequestValidationTest.shouldRejectAgeBelowMinimum`, `UserRequestValidationTest.shouldAcceptAgeAtInclusiveBoundaries`,
`UserControllerTest.shouldRejectAgeBelowMinimum`

---

## 9. Age above maximum

**Input:** baseline body with `"age": 101` (also tested: `150`, `Integer.MAX_VALUE`)

**Expected HTTP status:** `400 Bad Request`

**Expected behavior:** `VALIDATION_FAILED`, field `age`, message "Age must not exceed 100". `"age": 100` is **accepted**.

**Why the case matters**
Mirrors scenario 8 for the upper bound. `Integer.MAX_VALUE` confirms there is no overflow-related surprise.

**Tests:** `UserRequestValidationTest.shouldRejectAgeAboveMaximum`, `UserRequestValidationTest.shouldAcceptAgeAtInclusiveBoundaries`

---

## 10. Invalid status

**Input:** baseline body with `"status"` set to `"PENDING"`, `"active"`, `"Active"`, `" ACTIVE"` or `""`

**Expected HTTP status:** `400 Bad Request`

**Expected behavior**
- `VALIDATION_FAILED`, field `status`, message "Status must be ACTIVE or INACTIVE".
- Matching is **case-sensitive**: `"active"` is rejected.
- Missing status → "Status is required".

**Why the case matters**
If `status` were bound directly to a Java enum, an unknown value would fail during JSON parsing and return a
generic parse error with no field information. Validating it as a string gives the client a precise,
field-level message. Case-sensitivity is a deliberate decision and is pinned by tests.

**Tests:** `UserRequestValidationTest.shouldRejectUnsupportedStatus`, `UserRequestValidationTest.shouldAcceptSupportedStatuses`,
`UserControllerTest.shouldValidateBodyOnUpdateAsWell`

---

## 11. Malformed JSON

**Input**

```http
POST /api/users
Content-Type: application/json

{"name": "Asha",
```

**Expected HTTP status:** `400 Bad Request`

**Expected behavior**
- `error` = `MALFORMED_REQUEST`, message "Malformed JSON request body".
- The parser's internal message (line/column, Java class names) is **not** returned.
- A JSON array (`[]`) instead of an object → "Request body must be a JSON object".

**Why the case matters**
Without a handler, Spring's default response can expose parser internals and Java type names. A malformed body is a
client error and must never become a 500.

**Tests:** `UserControllerTest.shouldRejectMalformedJson`, `UserControllerTest.shouldRejectJsonArrayAsBody`

---

## 12. Empty request body

**Input:** `POST /api/users` with `Content-Type: application/json` and no body (or the literal `null`)

**Expected HTTP status:** `400 Bad Request`

**Expected behavior:** `error` = `MALFORMED_REQUEST`, message "Request body is missing".

**Why the case matters**
"No body" is different from "empty object" (`{}`, scenario 2): there is nothing to validate. The two cases get
distinct, accurate messages. A body sent with `Content-Type: text/plain` gets `415 UNSUPPORTED_MEDIA_TYPE`.

**Tests:** `UserControllerTest.shouldRejectEmptyRequestBody`, `UserControllerTest.shouldRejectUnsupportedContentType`

---

## 13. Non-numeric or overflowing ID

**Input:** `GET /api/users/abc` or `GET /api/users/99999999999999999999`

**Expected HTTP status:** `400 Bad Request`

**Expected behavior:** `error` = `INVALID_ID`, message "User id must be a positive number".

**Why the case matters**
Type conversion fails before the controller method runs, so this needs its own handler. Without one,
the response is a framework default instead of the API's error contract.

**Tests:** `UserControllerTest.shouldRejectNonNumericUserId`, `UserControllerTest.shouldRejectUserIdThatOverflowsLong`

---

## 14. Server-managed field in request body

**Input:** baseline body plus `"id": 999` (or `createdAt`, or a misspelled field such as `"emial"`)

**Expected HTTP status:** `400 Bad Request`

**Expected behavior:** `error` = `MALFORMED_REQUEST`, message "Unknown field 'id'".

**Why the case matters**
By default, Jackson silently ignores unknown fields. A client that sends `id` might believe it chose the id, and a
typo like `emial` would surface as a confusing "Email is required". Failing loudly makes both mistakes obvious.

**Tests:** `UserControllerTest.shouldRejectClientSuppliedId`

---

## 15. Wrong JSON type for age

**Input:** baseline body with `"age": 17.9` or `"age": "30"`

**Expected HTTP status:** `400 Bad Request`

**Expected behavior:** `error` = `MALFORMED_REQUEST`, message "Invalid value for field 'age'".

**Why the case matters**
With default settings, Jackson truncates `17.9` to `17` and converts `"30"` to `30`. Truncation is the dangerous
one: `18.5` would silently become `18`. Strict input settings in `application.properties` turn both into explicit errors.

**Tests:** `UserControllerTest.shouldRejectFractionalAgeInsteadOfTruncatingIt`, `UserControllerTest.shouldRejectAgeSentAsString`

---

## 16. Update keeping the same email

**Input:** `PUT /api/users/{id}` for an existing user, with that user's **current** email and a new age

**Expected HTTP status:** `200 OK`

**Expected behavior:** update succeeds; `createdAt` is unchanged; `updatedAt` is refreshed.

**Why the case matters**
A naive uniqueness check ("does this email exist?") rejects every update that does not change the email, because the
user's own record matches. The check must exclude the user being updated.

**Tests:** `UserApiIntegrationTest.shouldAllowUpdateWhenEmailIsUnchanged`, `UserApiIntegrationTest.shouldKeepCreatedAtUnchangedAfterUpdate`

---

## 17. Search by email

| Input                                         | Status | `error`              |
|-----------------------------------------------|--------|----------------------|
| `GET /api/users/search?email=ASHA.RAO@example.com` (exists) | 200 | – |
| `GET /api/users/search?email=nobody@example.com`            | 404 | `USER_NOT_FOUND` |
| `GET /api/users/search?email=%20%20`                        | 400 | `INVALID_PARAMETER` |
| `GET /api/users/search` (no parameter)                      | 400 | `INVALID_PARAMETER` |

**Why the case matters**
Search uses the same normalization as create, so lookups are case-insensitive. The `/search` route must not be
captured by `/{id}`; if it were, the request would fail with an `INVALID_ID` error. The integration lifecycle test proves the routing.

**Tests:** `UserServiceTest.shouldFindUserByEmailIgnoringCaseAndSurroundingSpaces`, `UserServiceTest.shouldRejectBlankSearchEmail`,
`UserControllerTest.shouldRejectSearchWithoutEmailParameter`, `UserApiIntegrationTest.shouldReturnNotFoundWhenSearchedEmailDoesNotExist`

---

## 18. Unexpected server error

**Input:** any request during which an unexpected exception occurs (simulated in tests)

**Expected HTTP status:** `500 Internal Server Error`

**Expected behavior**
- `error` = `INTERNAL_ERROR`, message "An unexpected error occurred".
- The exception message, class name, SQL and stack trace are **not** in the response; they are logged server-side.

**Why the case matters**
Internal details in error responses help attackers and confuse clients. This test simulates an exception
whose message contains a JDBC URL and asserts that the URL does not leak.

**Tests:** `UserControllerTest.shouldReturnInternalServerErrorWithoutLeakingDetails`

---

## Validation order

When a request has several problems, the first failing stage determines the response:

1. **Routing / HTTP method / Content-Type** → 404 `RESOURCE_NOT_FOUND`, 405, 415
2. **Path variable conversion** (`/api/users/abc`) → 400 `INVALID_ID`
3. **Body parsing** (missing, malformed, unknown field, wrong type) → 400 `MALFORMED_REQUEST`
4. **Bean validation** of the body → 400 `VALIDATION_FAILED`
5. **Business rules in the service**: id > 0 → existence → email uniqueness → 400 / 404 / 409

Example: `PUT /api/users/-1` with an invalid body returns `VALIDATION_FAILED` (stage 4), not `INVALID_ID` (stage 5).
