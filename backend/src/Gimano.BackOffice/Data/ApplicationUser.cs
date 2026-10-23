using Microsoft.AspNetCore.Identity;

namespace Gimano.BackOffice.Data;

/// <summary>A maintainer. Only emails listed in BackOffice:Maintainers can register or stay signed in.</summary>
public class ApplicationUser : IdentityUser
{
}
