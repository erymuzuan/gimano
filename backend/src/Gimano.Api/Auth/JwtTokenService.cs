using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;

namespace Gimano.Api.Auth;

public sealed class JwtOptions
{
    public const string Section = "Jwt";
    public string Issuer { get; set; } = "gimano";
    public string Audience { get; set; } = "gimano-app";
    /// <summary>HMAC-SHA256 key, at least 32 bytes. Override in production.</summary>
    public string SigningKey { get; set; } = "";
    public int LifetimeDays { get; set; } = 365;

    public SymmetricSecurityKey Key() => new(Encoding.UTF8.GetBytes(SigningKey));
}

public sealed class JwtTokenService(IOptions<JwtOptions> options, TimeProvider clock)
{
    public string Issue(Guid userId, string displayName)
    {
        var o = options.Value;
        var now = clock.GetUtcNow().UtcDateTime;
        var token = new JwtSecurityToken(
            o.Issuer, o.Audience,
            [
                new Claim(JwtRegisteredClaimNames.Sub, userId.ToString()),
                new Claim(JwtRegisteredClaimNames.Name, displayName),
                new Claim(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString("N")),
            ],
            notBefore: now.AddMinutes(-1),
            expires: now.AddDays(o.LifetimeDays),
            signingCredentials: new SigningCredentials(o.Key(), SecurityAlgorithms.HmacSha256));
        return new JwtSecurityTokenHandler().WriteToken(token);
    }
}

public static class ClaimsPrincipalExtensions
{
    public static Guid? UserId(this ClaimsPrincipal user) =>
        Guid.TryParse(user.FindFirstValue(JwtRegisteredClaimNames.Sub) ?? user.FindFirstValue(ClaimTypes.NameIdentifier), out var id) ? id : null;

    public static Guid RequiredUserId(this ClaimsPrincipal user) =>
        user.UserId() ?? throw new UnauthorizedAccessException("Missing user id claim.");
}
