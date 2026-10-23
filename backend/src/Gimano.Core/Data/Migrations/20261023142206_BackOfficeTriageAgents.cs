using System;
using Microsoft.EntityFrameworkCore.Migrations;
using Npgsql.EntityFrameworkCore.PostgreSQL.Metadata;

#nullable disable

namespace Gimano.Core.Data.Migrations
{
    /// <inheritdoc />
    public partial class BackOfficeTriageAgents : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "crash_signature",
                table: "feedback",
                type: "character varying(64)",
                maxLength: 64,
                nullable: true);

            migrationBuilder.AddColumn<Guid>(
                name: "duplicate_of_id",
                table: "feedback",
                type: "uuid",
                nullable: true);

            migrationBuilder.AddColumn<bool>(
                name: "screenshot_shared_with_agents",
                table: "feedback",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<string>(
                name: "severity",
                table: "feedback",
                type: "character varying(4)",
                maxLength: 4,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "triage_note",
                table: "feedback",
                type: "character varying(2000)",
                maxLength: 2000,
                nullable: true);

            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "updated_at",
                table: "feedback",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "analysis",
                table: "crash_groups",
                type: "character varying(20000)",
                maxLength: 20000,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "analysis_by",
                table: "crash_groups",
                type: "character varying(100)",
                maxLength: 100,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "external_url",
                table: "crash_groups",
                type: "character varying(300)",
                maxLength: 300,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "first_version",
                table: "crash_groups",
                type: "character varying(30)",
                maxLength: 30,
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "fixed_in_build",
                table: "crash_groups",
                type: "integer",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "fixed_in_version",
                table: "crash_groups",
                type: "character varying(30)",
                maxLength: 30,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "kind",
                table: "crash_groups",
                type: "character varying(8)",
                maxLength: 8,
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "latest_build",
                table: "crash_groups",
                type: "integer",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "latest_version",
                table: "crash_groups",
                type: "character varying(30)",
                maxLength: 30,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "message",
                table: "crash_groups",
                type: "character varying(500)",
                maxLength: 500,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "platform",
                table: "crash_groups",
                type: "character varying(10)",
                maxLength: 10,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "severity",
                table: "crash_groups",
                type: "character varying(4)",
                maxLength: 4,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "top_frame",
                table: "crash_groups",
                type: "character varying(300)",
                maxLength: 300,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "triage_note",
                table: "crash_groups",
                type: "character varying(2000)",
                maxLength: 2000,
                nullable: true);

            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "updated_at",
                table: "crash_groups",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.CreateTable(
                name: "agent_runs",
                columns: table => new
                {
                    id = table.Column<int>(type: "integer", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    title = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    target_type = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    target_id = table.Column<string>(type: "character varying(80)", maxLength: 80, nullable: false),
                    status = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: false),
                    agent = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: false),
                    started_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    branch = table.Column<string>(type: "character varying(120)", maxLength: 120, nullable: true),
                    pr_url = table.Column<string>(type: "character varying(300)", maxLength: 300, nullable: true),
                    summary = table.Column<string>(type: "character varying(20000)", maxLength: 20000, nullable: true),
                    steps = table.Column<string>(type: "jsonb", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_agent_runs", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "agent_tokens",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    name = table.Column<string>(type: "character varying(60)", maxLength: 60, nullable: false),
                    prefix = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: false),
                    token_hash = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    scopes = table.Column<string[]>(type: "text[]", nullable: false),
                    created_by = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    expires_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    revoked_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    last_used_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_agent_tokens", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "app_builds",
                columns: table => new
                {
                    id = table.Column<int>(type: "integer", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    platform = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    version = table.Column<string>(type: "character varying(30)", maxLength: 30, nullable: false),
                    build = table.Column<int>(type: "integer", nullable: false),
                    commit = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: false),
                    symbols_url = table.Column<string>(type: "character varying(300)", maxLength: 300, nullable: true),
                    released_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_app_builds", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "audit_log",
                columns: table => new
                {
                    id = table.Column<long>(type: "bigint", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    actor = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: false),
                    actor_kind = table.Column<string>(type: "character varying(8)", maxLength: 8, nullable: false),
                    action = table.Column<string>(type: "character varying(60)", maxLength: 60, nullable: false),
                    target_type = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: false),
                    target_id = table.Column<string>(type: "character varying(80)", maxLength: 80, nullable: true),
                    detail = table.Column<string>(type: "character varying(500)", maxLength: 500, nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_audit_log", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "reply_drafts",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    feedback_id = table.Column<Guid>(type: "uuid", nullable: false),
                    message = table.Column<string>(type: "character varying(1000)", maxLength: 1000, nullable: false),
                    new_status = table.Column<string>(type: "character varying(12)", maxLength: 12, nullable: true),
                    status = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    drafted_by = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    decided_by = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: true),
                    decided_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_reply_drafts", x => x.id);
                    table.ForeignKey(
                        name: "fk_reply_drafts_feedback_feedback_id",
                        column: x => x.feedback_id,
                        principalTable: "feedback",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "ix_feedback_crash_signature",
                table: "feedback",
                column: "crash_signature");

            migrationBuilder.CreateIndex(
                name: "ix_crash_groups_status_last_seen",
                table: "crash_groups",
                columns: new[] { "status", "last_seen" });

            migrationBuilder.CreateIndex(
                name: "ix_agent_runs_target_type_target_id",
                table: "agent_runs",
                columns: new[] { "target_type", "target_id" });

            migrationBuilder.CreateIndex(
                name: "ix_agent_runs_updated_at",
                table: "agent_runs",
                column: "updated_at");

            migrationBuilder.CreateIndex(
                name: "ix_agent_tokens_token_hash",
                table: "agent_tokens",
                column: "token_hash",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_app_builds_platform_build",
                table: "app_builds",
                columns: new[] { "platform", "build" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_audit_log_at",
                table: "audit_log",
                column: "at");

            migrationBuilder.CreateIndex(
                name: "ix_audit_log_target_type_target_id",
                table: "audit_log",
                columns: new[] { "target_type", "target_id" });

            migrationBuilder.CreateIndex(
                name: "ix_reply_drafts_feedback_id",
                table: "reply_drafts",
                column: "feedback_id");

            migrationBuilder.CreateIndex(
                name: "ix_reply_drafts_status_created_at",
                table: "reply_drafts",
                columns: new[] { "status", "created_at" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "agent_runs");

            migrationBuilder.DropTable(
                name: "agent_tokens");

            migrationBuilder.DropTable(
                name: "app_builds");

            migrationBuilder.DropTable(
                name: "audit_log");

            migrationBuilder.DropTable(
                name: "reply_drafts");

            migrationBuilder.DropIndex(
                name: "ix_feedback_crash_signature",
                table: "feedback");

            migrationBuilder.DropIndex(
                name: "ix_crash_groups_status_last_seen",
                table: "crash_groups");

            migrationBuilder.DropColumn(
                name: "crash_signature",
                table: "feedback");

            migrationBuilder.DropColumn(
                name: "duplicate_of_id",
                table: "feedback");

            migrationBuilder.DropColumn(
                name: "screenshot_shared_with_agents",
                table: "feedback");

            migrationBuilder.DropColumn(
                name: "severity",
                table: "feedback");

            migrationBuilder.DropColumn(
                name: "triage_note",
                table: "feedback");

            migrationBuilder.DropColumn(
                name: "updated_at",
                table: "feedback");

            migrationBuilder.DropColumn(
                name: "analysis",
                table: "crash_groups");

            migrationBuilder.DropColumn(
                name: "analysis_by",
                table: "crash_groups");

            migrationBuilder.DropColumn(
                name: "external_url",
                table: "crash_groups");

            migrationBuilder.DropColumn(
                name: "first_version",
                table: "crash_groups");

            migrationBuilder.DropColumn(
                name: "fixed_in_build",
                table: "crash_groups");

            migrationBuilder.DropColumn(
                name: "fixed_in_version",
                table: "crash_groups");

            migrationBuilder.DropColumn(
                name: "kind",
                table: "crash_groups");

            migrationBuilder.DropColumn(
                name: "latest_build",
                table: "crash_groups");

            migrationBuilder.DropColumn(
                name: "latest_version",
                table: "crash_groups");

            migrationBuilder.DropColumn(
                name: "message",
                table: "crash_groups");

            migrationBuilder.DropColumn(
                name: "platform",
                table: "crash_groups");

            migrationBuilder.DropColumn(
                name: "severity",
                table: "crash_groups");

            migrationBuilder.DropColumn(
                name: "top_frame",
                table: "crash_groups");

            migrationBuilder.DropColumn(
                name: "triage_note",
                table: "crash_groups");

            migrationBuilder.DropColumn(
                name: "updated_at",
                table: "crash_groups");
        }
    }
}
