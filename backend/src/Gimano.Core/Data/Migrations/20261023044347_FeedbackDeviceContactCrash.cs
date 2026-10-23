using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Gimano.Core.Data.Migrations
{
    /// <inheritdoc />
    public partial class FeedbackDeviceContactCrash : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.RenameColumn(
                name: "context",
                table: "feedback",
                newName: "device");

            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "closed_at",
                table: "feedback",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.AddColumn<double>(
                name: "lat",
                table: "feedback",
                type: "double precision",
                nullable: true);

            migrationBuilder.AddColumn<double>(
                name: "lng",
                table: "feedback",
                type: "double precision",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "place_id",
                table: "feedback",
                type: "character varying(40)",
                maxLength: 40,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "route_id",
                table: "feedback",
                type: "character varying(64)",
                maxLength: 64,
                nullable: true);

            migrationBuilder.CreateTable(
                name: "crash_groups",
                columns: table => new
                {
                    signature = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    exception = table.Column<string>(type: "character varying(300)", maxLength: 300, nullable: false),
                    count = table.Column<int>(type: "integer", nullable: false),
                    first_seen = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    last_seen = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    status = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    closed_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_crash_groups", x => x.signature);
                });

            migrationBuilder.CreateTable(
                name: "report_contacts",
                columns: table => new
                {
                    subject_type = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    subject_id = table.Column<Guid>(type: "uuid", nullable: false),
                    nonce = table.Column<byte[]>(type: "bytea", nullable: false),
                    ciphertext = table.Column<byte[]>(type: "bytea", nullable: false),
                    key_id = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_report_contacts", x => new { x.subject_type, x.subject_id });
                });

            migrationBuilder.CreateTable(
                name: "crash_reports",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: true),
                    signature = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    kind = table.Column<string>(type: "character varying(8)", maxLength: 8, nullable: false),
                    exception = table.Column<string>(type: "character varying(300)", maxLength: 300, nullable: false),
                    message = table.Column<string>(type: "character varying(500)", maxLength: 500, nullable: true),
                    stack = table.Column<string>(type: "text", nullable: true),
                    threads = table.Column<string>(type: "text", nullable: true),
                    screen = table.Column<string>(type: "character varying(32)", maxLength: 32, nullable: true),
                    uptime_s = table.Column<long>(type: "bigint", nullable: true),
                    device = table.Column<string>(type: "jsonb", nullable: true),
                    occurred_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_crash_reports", x => x.id);
                    table.ForeignKey(
                        name: "fk_crash_reports_crash_groups_signature",
                        column: x => x.signature,
                        principalTable: "crash_groups",
                        principalColumn: "signature",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_crash_reports_users_user_id",
                        column: x => x.user_id,
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.SetNull);
                });

            migrationBuilder.CreateIndex(
                name: "ix_feedback_status_closed_at",
                table: "feedback",
                columns: new[] { "status", "closed_at" });

            migrationBuilder.CreateIndex(
                name: "ix_crash_reports_created_at",
                table: "crash_reports",
                column: "created_at");

            migrationBuilder.CreateIndex(
                name: "ix_crash_reports_signature_created_at",
                table: "crash_reports",
                columns: new[] { "signature", "created_at" });

            migrationBuilder.CreateIndex(
                name: "ix_crash_reports_user_id_created_at",
                table: "crash_reports",
                columns: new[] { "user_id", "created_at" });

            migrationBuilder.CreateIndex(
                name: "ix_report_contacts_created_at",
                table: "report_contacts",
                column: "created_at");

            // Map the legacy 0.1.0 "context" json (PascalCase owned type) to device/lat/lng/route_id/place_id.
            migrationBuilder.Sql("""
                UPDATE feedback SET
                  lat = round((device->>'Lat')::numeric, 2)::float8,
                  lng = round((device->>'Lng')::numeric, 2)::float8,
                  route_id = device->>'RouteId',
                  place_id = device->>'PlaceId',
                  device = nullif(jsonb_strip_nulls(jsonb_build_object(
                    'appVersion', device->>'AppVersion', 'platform', device->>'Platform', 'osVersion', device->>'OsVersion',
                    'deviceModel', device->>'DeviceModel', 'locale', device->>'Locale')), '{}'::jsonb)
                WHERE device IS NOT NULL AND (device ? 'AppVersion' OR device ? 'Lat' OR device ? 'Platform' OR device ? 'RouteId');
                """);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "crash_reports");

            migrationBuilder.DropTable(
                name: "report_contacts");

            migrationBuilder.DropTable(
                name: "crash_groups");

            migrationBuilder.DropIndex(
                name: "ix_feedback_status_closed_at",
                table: "feedback");

            migrationBuilder.DropColumn(
                name: "closed_at",
                table: "feedback");

            migrationBuilder.DropColumn(
                name: "lat",
                table: "feedback");

            migrationBuilder.DropColumn(
                name: "lng",
                table: "feedback");

            migrationBuilder.DropColumn(
                name: "place_id",
                table: "feedback");

            migrationBuilder.DropColumn(
                name: "route_id",
                table: "feedback");

            migrationBuilder.RenameColumn(
                name: "device",
                table: "feedback",
                newName: "context");
        }
    }
}
