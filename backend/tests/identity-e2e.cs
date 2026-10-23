// End-to-end check of pseudonymous identity + signed requests + vetting against a running API that enforces
// signatures (Identity:RequireSignatures=true). Also a reference client implementation for the apps.
//   dotnet run backend/tests/identity-e2e.cs -- http://localhost:5091
#:package NSec.Cryptography@26.4.0
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using NSec.Cryptography;

var api = (args.Length > 0 ? args[0] : "http://localhost:5091").TrimEnd('/');
var http = new HttpClient { BaseAddress = new Uri(api) };
var ed = SignatureAlgorithm.Ed25519;
int failures = 0;
void Check(bool ok, string what) { Console.WriteLine($"{(ok ? "ok  " : "FAIL")} {what}"); if (!ok) failures++; }

(Key key, string pk) NewKey()
{
    var k = Key.Create(ed, new KeyCreationParameters { ExportPolicy = KeyExportPolicies.AllowPlaintextExport });
    return (k, Convert.ToBase64String(k.PublicKey.Export(KeyBlobFormat.RawPublicKey)));
}

async Task<(HttpResponseMessage, JsonNode?)> Send(HttpMethod method, string path, object? body, string? jwt, Key? signer, long? tsOverride = null, string? sigOverride = null)
{
    var bytes = body is null ? [] : JsonSerializer.SerializeToUtf8Bytes(body);
    var req = new HttpRequestMessage(method, path);
    if (body is not null) req.Content = new ByteArrayContent(bytes) { Headers = { ContentType = new("application/json") } };
    if (jwt is not null) req.Headers.Authorization = new("Bearer", jwt);
    if (signer is not null)
    {
        var ts = tsOverride ?? DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var canonical = $"GIMANO1\n{method.Method}\n{path}\n{ts}\n{Convert.ToHexStringLower(SHA256.HashData(bytes))}";
        req.Headers.Add("X-Gimano-Ts", ts.ToString());
        req.Headers.Add("X-Gimano-Sig", sigOverride ?? Convert.ToBase64String(ed.Sign(signer, Encoding.UTF8.GetBytes(canonical))));
    }
    var resp = await http.SendAsync(req);
    var text = await resp.Content.ReadAsStringAsync();
    return (resp, text.Length > 0 && text.TrimStart().StartsWith('{') || text.TrimStart().StartsWith('[') ? JsonNode.Parse(text) : null);
}

// 1. register a pseudonym
var (k1, pk1) = NewKey();
var (r, reg) = await Send(HttpMethod.Post, "/api/v1/auth/register", new { publicKey = pk1, platform = "android", locale = "ms-MY" }, null, null);
Check(r.IsSuccessStatusCode, $"register → {(int)r.StatusCode} {reg?["displayName"]}");
var jwt = reg!["token"]!.GetValue<string>(); var uid = reg["userId"]!.GetValue<string>();
Check((await Send(HttpMethod.Post, "/api/v1/auth/register", new { publicKey = pk1, platform = "android" }, null, null)).Item1.StatusCode == System.Net.HttpStatusCode.Conflict, "same key twice → 409");

// 2. signed requests
var report = new { type = "hazard", subtype = "pothole", lat = 3.1191, lng = 101.6214, heading = 90 };
var (u1, p1) = await Send(HttpMethod.Post, "/api/v1/reports", report, jwt, null);
Check((int)u1.StatusCode == 401 && p1?["type"]?.ToString().EndsWith("signature-missing") == true, $"unsigned report → {(int)u1.StatusCode} {p1?["type"]}");
var ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
var (s1, rep) = await Send(HttpMethod.Post, "/api/v1/reports", report, jwt, k1.Key(), ts);
Check((int)s1.StatusCode == 201, $"signed report → {(int)s1.StatusCode}");
var canonical = $"GIMANO1\nPOST\n/api/v1/reports\n{ts}\n{Convert.ToHexStringLower(SHA256.HashData(JsonSerializer.SerializeToUtf8Bytes(report)))}";
var sameSig = Convert.ToBase64String(ed.Sign(k1, Encoding.UTF8.GetBytes(canonical)));
var (s2, p2) = await Send(HttpMethod.Post, "/api/v1/reports", report, jwt, k1, ts, sameSig);
Check((int)s2.StatusCode == 401 && p2?["type"]?.ToString().EndsWith("signature-replayed") == true, $"replay → {(int)s2.StatusCode} {p2?["type"]}");
var (s3, p3) = await Send(HttpMethod.Post, "/api/v1/reports", report with { }, jwt, k1, ts - 1000);
Check(p3?["type"]?.ToString().EndsWith("signature-expired") == true, $"old ts → {(int)s3.StatusCode} {p3?["type"]}");
var (k9, _) = NewKey();
var (s4, p4) = await Send(HttpMethod.Post, "/api/v1/reports", report, jwt, k9);
Check(p4?["type"]?.ToString().EndsWith("signature-invalid") == true, $"wrong key → {(int)s4.StatusCode} {p4?["type"]}");

// 3. signature login + /me
var lts = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
var auth = Convert.ToBase64String(ed.Sign(k1, Encoding.UTF8.GetBytes($"GIMANO-AUTH\n{uid}\n{lts}")));
var (t1, tok) = await Send(HttpMethod.Post, "/api/v1/auth/token", new { userId = uid, ts = lts, sig = auth }, null, null);
Check(t1.IsSuccessStatusCode && tok?["token"] is not null, $"signature login → {(int)t1.StatusCode}");
var (m1, me) = await Send(HttpMethod.Get, "/api/v1/me", null, jwt, null);
Check(me?["tier"]?.ToString() == "baharu" && me["nextTier"]?["name"]?.ToString() == "perak", $"/me tier={me?["tier"]} accuracy={me?["accuracy"]} next={me?["nextTier"]?.ToJsonString()}");

// 4. key rotation
var (k2, pk2) = NewKey();
var rotSig = Convert.ToBase64String(ed.Sign(k1, Encoding.UTF8.GetBytes($"GIMANO-KEY\n{uid}\n{pk2}")));
Check((int)(await Send(HttpMethod.Post, "/api/v1/me/keys", new { publicKey = pk2 }, jwt, null)).Item1.StatusCode == 401, "rotation without sigByOldKey → 401");
Check((int)(await Send(HttpMethod.Post, "/api/v1/me/keys", new { publicKey = pk2, sigByOldKey = rotSig }, jwt, null)).Item1.StatusCode == 204, "rotation → 204");
Check((int)(await Send(HttpMethod.Post, "/api/v1/reports", report, jwt, k1)).Item1.StatusCode == 401, "old key no longer accepted");

// 5. vetting: contributor adds a place, another pseudonym verifies from nearby
var name = "Kedai Ujian Vetting " + Random.Shared.Next(1000, 9999);
var (c1, place) = await Send(HttpMethod.Post, "/api/v1/places", new { name, category = "convenience", lat = 3.12145, lng = 101.62510 }, jwt, k2);
Check((int)c1.StatusCode == 201 && place?["status"]?.ToString() == "pending", $"signed POST /places → {(int)c1.StatusCode} {place?["status"]}");
var pid = place?["id"]?.ToString();
var (k3, pk3) = NewKey();
var jwt3 = (await Send(HttpMethod.Post, "/api/v1/auth/register", new { publicKey = pk3, platform = "ios" }, null, null)).Item2!["token"]!.GetValue<string>();
var (n1, near) = await Send(HttpMethod.Get, "/api/v1/places/pending-nearby?cell=w2838r", null, jwt3, null);
Check(near is JsonArray arr && arr.Any(x => x?["id"]?.ToString() == pid), $"pending-nearby lists it ({(near as JsonArray)?.Count} places)");
var vote = new { answer = "yes", nameCorrect = true, locationCorrect = true, proximity = new { cell = "w2838r", withinM = 400 } };
var (v1, res) = await Send(HttpMethod.Post, $"/api/v1/places/{pid}/verify", vote, jwt3, k3);
Check(v1.IsSuccessStatusCode && res?["required"]?.GetValue<double>() == 3, $"verify → {(int)v1.StatusCode} {res?.ToJsonString()} (new accounts weigh 0)");
Check((int)(await Send(HttpMethod.Post, $"/api/v1/places/{pid}/verify", vote, jwt3, k3)).Item1.StatusCode == 409, "second vote → 409");
Check((int)(await Send(HttpMethod.Post, $"/api/v1/places/{pid}/verify", vote, jwt, k2)).Item1.StatusCode == 403, "contributor can't vote → 403");
var far = new { answer = "yes", proximity = new { cell = "w8cezs", withinM = 400 } };
var (k4, pk4) = NewKey();
var jwt4 = (await Send(HttpMethod.Post, "/api/v1/auth/register", new { publicKey = pk4, platform = "android" }, null, null)).Item2!["token"]!.GetValue<string>();
Check((int)(await Send(HttpMethod.Post, $"/api/v1/places/{pid}/verify", far, jwt4, k4)).Item1.StatusCode == 403, "far-away cell → 403");
var (n2, near3) = await Send(HttpMethod.Get, "/api/v1/places/pending-nearby?cell=w2838r", null, jwt3, null);
Check(near3 is JsonArray a3 && !a3.Any(x => x?["id"]?.ToString() == pid), "pending-nearby hides places you already voted on");

// cleanup: the contributor deletes the test place; accounts are deleted
await Send(HttpMethod.Delete, $"/api/v1/places/{pid}", null, jwt, k2);
foreach (var j in new[] { jwt, jwt3, jwt4 }) await Send(HttpMethod.Delete, "/api/v1/me", null, j, null);
Console.WriteLine(failures == 0 ? "ALL OK" : $"{failures} FAILED");
return failures == 0 ? 0 : 1;

static class KeyExt { public static Key Key(this Key k) => k; }
