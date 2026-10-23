using Gimano.Core.BackOffice;
using Gimano.Core.Contracts;
using Microsoft.AspNetCore.Components;
using Microsoft.AspNetCore.Components.Authorization;

namespace Gimano.BackOffice.Components.Shared;

/// <summary>
/// Base for back-office pages: a TriageService (and its DbContext) scoped to the page, the signed-in maintainer as
/// an <see cref="Actor"/>, and friendly handling of the service's validation and permission errors.
/// </summary>
public abstract class BackOfficePage : OwningComponentBase<TriageService>
{
    [CascadingParameter] private Task<AuthenticationState> AuthState { get; set; } = default!;

    protected string? Error { get; set; }
    protected string? Notice { get; set; }

    protected TriageService Triage => Service;

    protected async Task<Actor> MeAsync()
    {
        var user = (await AuthState).User;
        return Actor.Maintainer(user.Identity?.Name ?? throw new UnauthorizedAccessException("Not signed in."));
    }

    /// <summary>Runs an action, turning expected service errors into a message on the page.</summary>
    protected async Task<bool> TryAsync(Func<Task> action, string? success = null)
    {
        Error = null;
        Notice = null;
        try
        {
            await action();
            Notice = success;
            return true;
        }
        catch (Exception ex) when (ex is ValidationException or NotFoundException or ConflictException or ForbiddenException or UnauthorizedAccessException)
        {
            Error = ex.Message;
            return false;
        }
    }
}
