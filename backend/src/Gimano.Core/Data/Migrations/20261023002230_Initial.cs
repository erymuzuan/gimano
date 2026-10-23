using System;
using System.Collections.Generic;
using Microsoft.EntityFrameworkCore.Migrations;
using NetTopologySuite.Geometries;
using Npgsql.EntityFrameworkCore.PostgreSQL.Metadata;

#nullable disable

namespace Gimano.Core.Data.Migrations
{
    /// <inheritdoc />
    public partial class Initial : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AlterDatabase()
                .Annotation("Npgsql:PostgresExtension:pg_trgm", ",,")
                .Annotation("Npgsql:PostgresExtension:postgis", ",,");

            migrationBuilder.CreateTable(
                name: "driver_positions",
                columns: table => new
                {
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    location = table.Column<Point>(type: "geography (point, 4326)", nullable: false),
                    heading = table.Column<double>(type: "double precision", nullable: false),
                    speed_kmh = table.Column<double>(type: "double precision", nullable: false),
                    mood = table.Column<string>(type: "text", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_driver_positions", x => x.user_id);
                });

            migrationBuilder.CreateTable(
                name: "jams",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    level = table.Column<int>(type: "integer", nullable: false),
                    speed_kmh = table.Column<double>(type: "double precision", nullable: false),
                    delay_s = table.Column<double>(type: "double precision", nullable: false),
                    length_m = table.Column<double>(type: "double precision", nullable: false),
                    geom = table.Column<LineString>(type: "geography (linestring, 4326)", nullable: false),
                    street = table.Column<string>(type: "text", nullable: true),
                    anchor_segment_id = table.Column<long>(type: "bigint", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_jams", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "places",
                columns: table => new
                {
                    id = table.Column<string>(type: "character varying(24)", maxLength: 24, nullable: false),
                    name = table.Column<string>(type: "text", nullable: false),
                    category = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: false),
                    brand = table.Column<string>(type: "text", nullable: true),
                    address = table.Column<string>(type: "text", nullable: false),
                    location = table.Column<Point>(type: "geography (point, 4326)", nullable: false),
                    country = table.Column<string>(type: "character varying(2)", maxLength: 2, nullable: false),
                    phone = table.Column<string>(type: "text", nullable: true),
                    website = table.Column<string>(type: "text", nullable: true),
                    opening_hours = table.Column<string>(type: "text", nullable: true),
                    amenities = table.Column<List<string>>(type: "text[]", nullable: false),
                    tags = table.Column<Dictionary<string, string>>(type: "jsonb", nullable: false),
                    osm_type = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    osm_id = table.Column<long>(type: "bigint", nullable: false),
                    popularity = table.Column<double>(type: "double precision", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_places", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "road_segments",
                columns: table => new
                {
                    id = table.Column<long>(type: "bigint", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    osm_way_id = table.Column<long>(type: "bigint", nullable: false),
                    seq = table.Column<int>(type: "integer", nullable: false),
                    name = table.Column<string>(type: "text", nullable: true),
                    @ref = table.Column<string>(name: "ref", type: "text", nullable: true),
                    highway = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: false),
                    oneway = table.Column<bool>(type: "boolean", nullable: false),
                    free_flow_kmh = table.Column<double>(type: "double precision", nullable: false),
                    length_m = table.Column<double>(type: "double precision", nullable: false),
                    from_node = table.Column<long>(type: "bigint", nullable: false),
                    to_node = table.Column<long>(type: "bigint", nullable: false),
                    geom = table.Column<LineString>(type: "geography (linestring, 4326)", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_road_segments", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "segment_speeds",
                columns: table => new
                {
                    segment_id = table.Column<long>(type: "bigint", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    mean_speed_kmh = table.Column<double>(type: "double precision", nullable: false),
                    free_flow_kmh = table.Column<double>(type: "double precision", nullable: false),
                    samples = table.Column<int>(type: "integer", nullable: false),
                    distinct_users = table.Column<int>(type: "integer", nullable: false),
                    level = table.Column<int>(type: "integer", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_segment_speeds", x => x.segment_id);
                });

            migrationBuilder.CreateTable(
                name: "users",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    device_id = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    platform = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    display_name = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: false),
                    mood = table.Column<string>(type: "character varying(30)", maxLength: 30, nullable: false),
                    vehicle = table.Column<string>(type: "character varying(12)", maxLength: 12, nullable: false),
                    locale = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: false),
                    points = table.Column<int>(type: "integer", nullable: false),
                    reputation = table.Column<double>(type: "double precision", nullable: false),
                    joined_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    last_seen_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_users", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "recents",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    name = table.Column<string>(type: "text", nullable: false),
                    address = table.Column<string>(type: "text", nullable: false),
                    location = table.Column<Point>(type: "geography (point, 4326)", nullable: false),
                    place_id = table.Column<string>(type: "text", nullable: true),
                    last_used_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_recents", x => x.id);
                    table.ForeignKey(
                        name: "fk_recents_users_user_id",
                        column: x => x.user_id,
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "reports",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    type = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: false),
                    subtype = table.Column<string>(type: "character varying(30)", maxLength: 30, nullable: true),
                    location = table.Column<Point>(type: "geography (point, 4326)", nullable: false),
                    end_location = table.Column<Point>(type: "geography (point, 4326)", nullable: true),
                    heading = table.Column<double>(type: "double precision", nullable: true),
                    street = table.Column<string>(type: "text", nullable: true),
                    comment = table.Column<string>(type: "character varying(500)", maxLength: 500, nullable: true),
                    reporter_id = table.Column<Guid>(type: "uuid", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    expires_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    confirmations = table.Column<int>(type: "integer", nullable: false),
                    thanks = table.Column<int>(type: "integer", nullable: false),
                    status = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    xmin = table.Column<uint>(type: "xid", rowVersion: true, nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_reports", x => x.id);
                    table.ForeignKey(
                        name: "fk_reports_users_reporter_id",
                        column: x => x.reporter_id,
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "saved_places",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    kind = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    name = table.Column<string>(type: "text", nullable: false),
                    location = table.Column<Point>(type: "geography (point, 4326)", nullable: false),
                    address = table.Column<string>(type: "text", nullable: false),
                    place_id = table.Column<string>(type: "text", nullable: true),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_saved_places", x => x.id);
                    table.ForeignKey(
                        name: "fk_saved_places_users_user_id",
                        column: x => x.user_id,
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "report_votes",
                columns: table => new
                {
                    report_id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    still_there = table.Column<bool>(type: "boolean", nullable: false),
                    weight = table.Column<double>(type: "double precision", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_report_votes", x => new { x.report_id, x.user_id });
                    table.ForeignKey(
                        name: "fk_report_votes_reports_report_id",
                        column: x => x.report_id,
                        principalTable: "reports",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "ix_driver_positions_location",
                table: "driver_positions",
                column: "location")
                .Annotation("Npgsql:IndexMethod", "gist");

            migrationBuilder.CreateIndex(
                name: "ix_driver_positions_updated_at",
                table: "driver_positions",
                column: "updated_at");

            migrationBuilder.CreateIndex(
                name: "ix_jams_anchor_segment_id",
                table: "jams",
                column: "anchor_segment_id");

            migrationBuilder.CreateIndex(
                name: "ix_jams_geom",
                table: "jams",
                column: "geom")
                .Annotation("Npgsql:IndexMethod", "gist");

            migrationBuilder.CreateIndex(
                name: "ix_places_category",
                table: "places",
                column: "category");

            migrationBuilder.CreateIndex(
                name: "ix_places_location",
                table: "places",
                column: "location")
                .Annotation("Npgsql:IndexMethod", "gist");

            migrationBuilder.CreateIndex(
                name: "ix_places_name",
                table: "places",
                column: "name")
                .Annotation("Npgsql:IndexMethod", "gin")
                .Annotation("Npgsql:IndexOperators", new[] { "gin_trgm_ops" });

            migrationBuilder.CreateIndex(
                name: "ix_recents_user_id_last_used_at",
                table: "recents",
                columns: new[] { "user_id", "last_used_at" });

            migrationBuilder.CreateIndex(
                name: "ix_reports_location",
                table: "reports",
                column: "location")
                .Annotation("Npgsql:IndexMethod", "gist");

            migrationBuilder.CreateIndex(
                name: "ix_reports_reporter_id",
                table: "reports",
                column: "reporter_id");

            migrationBuilder.CreateIndex(
                name: "ix_reports_status_expires_at",
                table: "reports",
                columns: new[] { "status", "expires_at" });

            migrationBuilder.CreateIndex(
                name: "ix_road_segments_from_node",
                table: "road_segments",
                column: "from_node");

            migrationBuilder.CreateIndex(
                name: "ix_road_segments_geom",
                table: "road_segments",
                column: "geom")
                .Annotation("Npgsql:IndexMethod", "gist");

            migrationBuilder.CreateIndex(
                name: "ix_road_segments_osm_way_id_seq",
                table: "road_segments",
                columns: new[] { "osm_way_id", "seq" });

            migrationBuilder.CreateIndex(
                name: "ix_road_segments_to_node",
                table: "road_segments",
                column: "to_node");

            migrationBuilder.CreateIndex(
                name: "ix_saved_places_user_id_kind",
                table: "saved_places",
                columns: new[] { "user_id", "kind" });

            migrationBuilder.CreateIndex(
                name: "ix_segment_speeds_updated_at",
                table: "segment_speeds",
                column: "updated_at");

            migrationBuilder.CreateIndex(
                name: "ix_users_device_id",
                table: "users",
                column: "device_id",
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "driver_positions");

            migrationBuilder.DropTable(
                name: "jams");

            migrationBuilder.DropTable(
                name: "places");

            migrationBuilder.DropTable(
                name: "recents");

            migrationBuilder.DropTable(
                name: "report_votes");

            migrationBuilder.DropTable(
                name: "road_segments");

            migrationBuilder.DropTable(
                name: "saved_places");

            migrationBuilder.DropTable(
                name: "segment_speeds");

            migrationBuilder.DropTable(
                name: "reports");

            migrationBuilder.DropTable(
                name: "users");
        }
    }
}
