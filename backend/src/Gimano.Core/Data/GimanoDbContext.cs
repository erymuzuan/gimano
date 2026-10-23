using Gimano.Core.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;
using Npgsql;

namespace Gimano.Core.Data;

public sealed class GimanoDbContext(DbContextOptions<GimanoDbContext> options) : DbContext(options)
{
    public DbSet<User> Users => Set<User>();
    public DbSet<SavedPlace> SavedPlaces => Set<SavedPlace>();
    public DbSet<RecentDestination> Recents => Set<RecentDestination>();
    public DbSet<Place> Places => Set<Place>();
    public DbSet<Report> Reports => Set<Report>();
    public DbSet<ReportVote> ReportVotes => Set<ReportVote>();
    public DbSet<RoadSegment> RoadSegments => Set<RoadSegment>();
    public DbSet<SegmentSpeed> SegmentSpeeds => Set<SegmentSpeed>();
    public DbSet<Jam> Jams => Set<Jam>();
    public DbSet<Feedback> Feedback => Set<Feedback>();
    public DbSet<TollRate> TollRates => Set<TollRate>();

    protected override void OnModelCreating(ModelBuilder b)
    {
        b.HasPostgresExtension("postgis");
        b.HasPostgresExtension("pg_trgm");

        b.Entity<User>(e =>
        {
            e.HasIndex(x => x.DeviceId).IsUnique();
            e.Property(x => x.DeviceId).HasMaxLength(200);
            e.Property(x => x.DisplayName).HasMaxLength(40);
            e.Property(x => x.Mood).HasMaxLength(30);
            e.Property(x => x.Locale).HasMaxLength(20);
            e.Property(x => x.Platform).HasConversion<string>().HasMaxLength(10);
            e.Property(x => x.Vehicle).HasConversion<string>().HasMaxLength(12);
            e.Ignore(x => x.Level);
        });

        b.Entity<SavedPlace>(e =>
        {
            e.Property(x => x.Kind).HasConversion<string>().HasMaxLength(10);
            e.Property(x => x.Icon).HasConversion<string>().HasMaxLength(12);
            e.Ignore(x => x.EffectiveIcon);
            e.Property(x => x.Location).HasColumnType("geography (point, 4326)");
            e.HasIndex(x => new { x.UserId, x.Kind });
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<RecentDestination>(e =>
        {
            e.ToTable("recents");
            e.Property(x => x.Location).HasColumnType("geography (point, 4326)");
            e.HasIndex(x => new { x.UserId, x.LastUsedAt });
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<Place>(e =>
        {
            e.Property(x => x.Id).HasMaxLength(24);
            e.Property(x => x.Category).HasConversion<string>().HasMaxLength(20);
            e.Property(x => x.Country).HasMaxLength(2);
            e.Property(x => x.OsmType).HasMaxLength(10);
            e.Property(x => x.Location).HasColumnType("geography (point, 4326)");
            e.Property(x => x.Tags).HasColumnType("jsonb");
            e.HasIndex(x => x.Location).HasMethod("gist");
            e.HasIndex(x => x.Category);
            e.HasIndex(x => x.Name).HasMethod("gin").HasOperators("gin_trgm_ops");
        });

        b.Entity<Report>(e =>
        {
            e.Property(x => x.Type).HasConversion<string>().HasMaxLength(20);
            e.Property(x => x.Status).HasConversion<string>().HasMaxLength(10);
            e.Property(x => x.Subtype).HasMaxLength(30);
            e.Property(x => x.Comment).HasMaxLength(500);
            e.Property(x => x.Location).HasColumnType("geography (point, 4326)");
            e.Property(x => x.EndLocation).HasColumnType("geography (point, 4326)");
            e.Property(x => x.Version).IsRowVersion();
            e.HasIndex(x => x.Location).HasMethod("gist");
            e.HasIndex(x => new { x.Status, x.ExpiresAt });
            e.HasOne(x => x.Reporter).WithMany().HasForeignKey(x => x.ReporterId).OnDelete(DeleteBehavior.SetNull);
        });

        b.Entity<ReportVote>(e =>
        {
            e.HasKey(x => new { x.ReportId, x.UserId });
            e.HasOne<Report>().WithMany().HasForeignKey(x => x.ReportId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<RoadSegment>(e =>
        {
            e.Property(x => x.Geom).HasColumnType("geography (linestring, 4326)");
            e.Property(x => x.Highway).HasMaxLength(20);
            e.HasIndex(x => x.Geom).HasMethod("gist");
            e.HasIndex(x => x.FromNode);
            e.HasIndex(x => x.ToNode);
            e.HasIndex(x => new { x.OsmWayId, x.Seq });
        });

        b.Entity<SegmentSpeed>(e =>
        {
            e.HasKey(x => x.SegmentId);
            e.HasIndex(x => x.UpdatedAt);
        });

        b.Entity<Jam>(e =>
        {
            e.Property(x => x.Geom).HasColumnType("geography (linestring, 4326)");
            e.HasIndex(x => x.Geom).HasMethod("gist");
            e.HasIndex(x => x.AnchorSegmentId);
        });

        b.Entity<Feedback>(e =>
        {
            e.ToTable("feedback");
            e.Property(x => x.Category).HasConversion<string>().HasMaxLength(12);
            e.Property(x => x.Screen).HasConversion<string>().HasMaxLength(16);
            e.Property(x => x.Status).HasConversion<string>().HasMaxLength(12);
            e.Property(x => x.Message).HasMaxLength(2000);
            e.Property(x => x.ScreenshotContentType).HasMaxLength(20);
            e.Property(x => x.ExternalUrl).HasMaxLength(300);
            e.OwnsOne(x => x.Context, o => o.ToJson());
            e.HasIndex(x => new { x.UserId, x.CreatedAt });
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.SetNull);
        });

        b.Entity<TollRate>(e =>
        {
            e.Property(x => x.System).HasConversion<string>().HasMaxLength(12);
            e.Property(x => x.Class0Myr).HasPrecision(8, 2);
            e.Property(x => x.Class1Myr).HasPrecision(8, 2);
            e.HasIndex(x => x.MatchedPlaceIds).HasMethod("gin");
        });
    }
}

public static class GimanoDb
{
    public const string ConnectionStringName = "Postgres";
    public const string DefaultConnectionString = "Host=localhost;Port=5432;Database=gimano;Username=gimano;Password=gimano";

    public static NpgsqlDataSource BuildDataSource(string connectionString)
    {
        var dsb = new NpgsqlDataSourceBuilder(connectionString);
        dsb.UseNetTopologySuite(geographyAsDefault: true);
        dsb.EnableDynamicJson();
        return dsb.Build();
    }

    public static void Configure(DbContextOptionsBuilder o, NpgsqlDataSource ds) =>
        o.UseNpgsql(ds, npg =>
            {
                npg.UseNetTopologySuite(geographyAsDefault: true);
                npg.MigrationsAssembly(typeof(GimanoDbContext).Assembly.FullName);
            })
            .UseSnakeCaseNamingConvention();
}

/// <summary>Used by <c>dotnet ef</c> at design time.</summary>
public sealed class DesignTimeFactory : IDesignTimeDbContextFactory<GimanoDbContext>
{
    public GimanoDbContext CreateDbContext(string[] args)
    {
        var cs = Environment.GetEnvironmentVariable("ConnectionStrings__Postgres") ?? GimanoDb.DefaultConnectionString;
        var o = new DbContextOptionsBuilder<GimanoDbContext>();
        GimanoDb.Configure(o, GimanoDb.BuildDataSource(cs));
        return new GimanoDbContext(o.Options);
    }
}
