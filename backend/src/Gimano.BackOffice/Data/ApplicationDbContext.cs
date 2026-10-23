using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;

namespace Gimano.BackOffice.Data;

/// <summary>Maintainer accounts (ASP.NET Core Identity + passkeys), in their own "backoffice" schema of the Gimano database.</summary>
public class ApplicationDbContext(DbContextOptions<ApplicationDbContext> options) : IdentityDbContext<ApplicationUser>(options)
{
    public const string Schema = "backoffice";

    public static void Configure(DbContextOptionsBuilder o, string connectionString) =>
        o.UseNpgsql(connectionString, npg => npg.MigrationsHistoryTable("__EFMigrationsHistory", Schema));

    public DbSet<MaintainerInvite> MaintainerInvites => Set<MaintainerInvite>();

    protected override void OnModelCreating(ModelBuilder builder)
    {
        builder.HasDefaultSchema(Schema);
        base.OnModelCreating(builder);
        builder.Entity<MaintainerInvite>(e =>
        {
            e.ToTable("MaintainerInvites");
            e.Property(x => x.NormalizedEmail).HasMaxLength(256);
            e.Property(x => x.TokenHash).HasMaxLength(64);
            e.HasIndex(x => x.TokenHash).IsUnique();
            e.HasIndex(x => x.NormalizedEmail);
        });
    }
}
