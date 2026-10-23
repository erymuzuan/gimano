using System;
using System.Collections.Generic;
using Microsoft.EntityFrameworkCore.Migrations;
using NetTopologySuite.Geometries;
using Npgsql.EntityFrameworkCore.PostgreSQL.Metadata;

#nullable disable

namespace Gimano.Core.Data.Migrations
{
    /// <inheritdoc />
    public partial class Issues : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "issue_salts",
                columns: table => new
                {
                    issue_id = table.Column<Guid>(type: "uuid", nullable: false),
                    salt = table.Column<byte[]>(type: "bytea", nullable: false),
                    resolved_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_issue_salts", x => x.issue_id);
                });

            migrationBuilder.CreateTable(
                name: "issue_votes",
                columns: table => new
                {
                    id = table.Column<long>(type: "bigint", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    issue_id = table.Column<Guid>(type: "uuid", nullable: false),
                    voter_id = table.Column<Guid>(type: "uuid", nullable: true),
                    voter_hash = table.Column<byte[]>(type: "bytea", nullable: true),
                    answer = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    weight = table.Column<double>(type: "double precision", nullable: false),
                    is_report = table.Column<bool>(type: "boolean", nullable: false),
                    emas = table.Column<bool>(type: "boolean", nullable: false),
                    scored = table.Column<bool>(type: "boolean", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    day = table.Column<DateOnly>(type: "date", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_issue_votes", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "issues",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    kind = table.Column<string>(type: "character varying(32)", maxLength: 32, nullable: false),
                    subtype = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: true),
                    state = table.Column<string>(type: "character varying(12)", maxLength: 12, nullable: false),
                    source = table.Column<string>(type: "character varying(12)", maxLength: 12, nullable: false),
                    way_id = table.Column<long>(type: "bigint", nullable: true),
                    seq = table.Column<int>(type: "integer", nullable: true),
                    direction = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    node_id = table.Column<long>(type: "bigint", nullable: true),
                    place_id = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: true),
                    highway = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: true),
                    road_name = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: true),
                    major_road = table.Column<bool>(type: "boolean", nullable: false),
                    location = table.Column<Point>(type: "geography (point, 4326)", nullable: false),
                    vehicle = table.Column<string>(type: "character varying(12)", maxLength: 12, nullable: true),
                    created_by = table.Column<Guid>(type: "uuid", nullable: true),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    expires_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    confirmed_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    closed_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    last_evidence_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    weighted_still = table.Column<double>(type: "double precision", nullable: false),
                    weighted_cleared = table.Column<double>(type: "double precision", nullable: false),
                    emas_support = table.Column<bool>(type: "boolean", nullable: false),
                    confidence = table.Column<double>(type: "double precision", nullable: false),
                    still_votes = table.Column<int>(type: "integer", nullable: false),
                    cleared_votes = table.Column<int>(type: "integer", nullable: false),
                    payload = table.Column<Dictionary<string, string>>(type: "jsonb", nullable: false),
                    exported_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    history = table.Column<string>(type: "jsonb", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_issues", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "segment_traversal_counts",
                columns: table => new
                {
                    way_id = table.Column<long>(type: "bigint", nullable: false),
                    seq = table.Column<int>(type: "integer", nullable: false),
                    direction = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    hour = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    count = table.Column<int>(type: "integer", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_segment_traversal_counts", x => new { x.way_id, x.seq, x.direction, x.hour });
                });

            migrationBuilder.CreateIndex(
                name: "ix_issue_votes_issue_id_voter_id",
                table: "issue_votes",
                columns: new[] { "issue_id", "voter_id" },
                unique: true,
                filter: "voter_id IS NOT NULL");

            migrationBuilder.CreateIndex(
                name: "ix_issue_votes_voter_id_day",
                table: "issue_votes",
                columns: new[] { "voter_id", "day" });

            migrationBuilder.CreateIndex(
                name: "ix_issues_created_by",
                table: "issues",
                column: "created_by");

            migrationBuilder.CreateIndex(
                name: "ix_issues_location",
                table: "issues",
                column: "location")
                .Annotation("Npgsql:IndexMethod", "gist");

            migrationBuilder.CreateIndex(
                name: "ix_issues_place_id",
                table: "issues",
                column: "place_id");

            migrationBuilder.CreateIndex(
                name: "ix_issues_state_kind",
                table: "issues",
                columns: new[] { "state", "kind" });

            migrationBuilder.CreateIndex(
                name: "ix_issues_way_id_seq",
                table: "issues",
                columns: new[] { "way_id", "seq" });

            migrationBuilder.CreateIndex(
                name: "ix_segment_traversal_counts_hour",
                table: "segment_traversal_counts",
                column: "hour");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "issue_salts");

            migrationBuilder.DropTable(
                name: "issue_votes");

            migrationBuilder.DropTable(
                name: "issues");

            migrationBuilder.DropTable(
                name: "segment_traversal_counts");
        }
    }
}
