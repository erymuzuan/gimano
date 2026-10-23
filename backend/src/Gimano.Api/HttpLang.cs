using Gimano.Core.Community;

namespace Gimano.Api;

public static class HttpLang
{
    /// <summary>"ms" when Accept-Language prefers Malay, else "en" (server-originated text only; tier names stay Malay).</summary>
    public static string Lang(this HttpContext http) => L10n.FromAcceptLanguage(http.Request.Headers.AcceptLanguage.ToString());
}
