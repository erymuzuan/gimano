using System;
using System.Collections.Generic;
using Gimano.Core.Contracts;
using Gimano.Core.Domain;
using Microsoft.EntityFrameworkCore.Migrations;
using Npgsql.EntityFrameworkCore.PostgreSQL.Metadata;

#nullable disable

namespace Gimano.Core.Data.Migrations
{
    /// <inheritdoc />
    public partial class BackOfficeFeedbackCrashes : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "inbox_read_up_to",
                table: "users",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "closed_at",
                table: "feedback",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "crash_signature",
                table: "feedback",
                type: "character varying(64)",
                maxLength: 64,
                nullable: true);

            migrationBuilder.AddColumn<DeviceInfo>(
                name: "device",
                table: "feedback",
                type: "jsonb",
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
                nullable: false,
                defaultValue: new DateTimeOffset(new DateTime(1, 1, 1, 0, 0, 0, 0, DateTimeKind.Unspecified), new TimeSpan(0, 0, 0, 0, 0)));

            // Existing rows: updated = created; closed items start their contact clock at creation (they carry no contacts yet).
            migrationBuilder.Sql("UPDATE feedback SET updated_at = created_at;");
            migrationBuilder.Sql("UPDATE feedback SET closed_at = created_at WHERE status IN ('Done', 'WontFix');");

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
                    agent = table.Column<string>(type: "character varying(60)", maxLength: 60, nullable: false),
                    started_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    branch = table.Column<string>(type: "character varying(120)", maxLength: 120, nullable: true),
                    pr_url = table.Column<string>(type: "character varying(300)", maxLength: 300, nullable: true),
                    summary = table.Column<string>(type: "character varying(20000)", maxLength: 20000, nullable: true),
                    steps = table.Column<List<AgentRunStep>>(type: "jsonb", nullable: false)
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
                name: "crash_groups",
                columns: table => new
                {
                    signature = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    platform = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    kind = table.Column<string>(type: "character varying(8)", maxLength: 8, nullable: false),
                    exception = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    message = table.Column<string>(type: "character varying(500)", maxLength: 500, nullable: true),
                    top_frame = table.Column<string>(type: "character varying(300)", maxLength: 300, nullable: true),
                    status = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    severity = table.Column<string>(type: "character varying(4)", maxLength: 4, nullable: true),
                    event_count = table.Column<int>(type: "integer", nullable: false),
                    first_seen_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    last_seen_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    first_version = table.Column<string>(type: "character varying(30)", maxLength: 30, nullable: true),
                    latest_version = table.Column<string>(type: "character varying(30)", maxLength: 30, nullable: true),
                    latest_build = table.Column<int>(type: "integer", nullable: true),
                    fixed_in_build = table.Column<int>(type: "integer", nullable: true),
                    fixed_in_version = table.Column<string>(type: "character varying(30)", maxLength: 30, nullable: true),
                    external_url = table.Column<string>(type: "character varying(300)", maxLength: 300, nullable: true),
                    triage_note = table.Column<string>(type: "character varying(2000)", maxLength: 2000, nullable: true),
                    analysis = table.Column<string>(type: "character varying(20000)", maxLength: 20000, nullable: true),
                    analysis_by = table.Column<string>(type: "character varying(60)", maxLength: 60, nullable: true),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_crash_groups", x => x.signature);
                });

            migrationBuilder.CreateTable(
                name: "inbox_items",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    kind = table.Column<string>(type: "character varying(30)", maxLength: 30, nullable: false),
                    title = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    body = table.Column<string>(type: "character varying(1000)", maxLength: 1000, nullable: false),
                    ref_type = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: true),
                    ref_id = table.Column<string>(type: "character varying(80)", maxLength: 80, nullable: true),
                    count = table.Column<int>(type: "integer", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_inbox_items", x => x.id);
                    table.ForeignKey(
                        name: "fk_inbox_items_users_user_id",
                        column: x => x.user_id,
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
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

            migrationBuilder.CreateTable(
                name: "crash_events",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    signature = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    occurred_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    received_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    kind = table.Column<string>(type: "character varying(8)", maxLength: 8, nullable: false),
                    exception = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    message = table.Column<string>(type: "character varying(500)", maxLength: 500, nullable: true),
                    stack = table.Column<string>(type: "character varying(32768)", maxLength: 32768, nullable: false),
                    threads = table.Column<string>(type: "character varying(32768)", maxLength: 32768, nullable: true),
                    screen = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: true),
                    uptime_s = table.Column<long>(type: "bigint", nullable: true),
                    device = table.Column<DeviceInfo>(type: "jsonb", nullable: false),
                    has_contact = table.Column<bool>(type: "boolean", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_crash_events", x => x.id);
                    table.ForeignKey(
                        name: "fk_crash_events_crash_groups_signature",
                        column: x => x.signature,
                        principalTable: "crash_groups",
                        principalColumn: "signature",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "report_contacts",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    feedback_id = table.Column<Guid>(type: "uuid", nullable: true),
                    crash_event_id = table.Column<Guid>(type: "uuid", nullable: true),
                    @sealed = table.Column<byte[]>(name: "sealed", type: "bytea", nullable: false),
                    has_email = table.Column<bool>(type: "boolean", nullable: false),
                    has_whatsapp = table.Column<bool>(type: "boolean", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    expires_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_report_contacts", x => x.id);
                    table.ForeignKey(
                        name: "fk_report_contacts_crash_events_crash_event_id",
                        column: x => x.crash_event_id,
                        principalTable: "crash_events",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_report_contacts_feedback_feedback_id",
                        column: x => x.feedback_id,
                        principalTable: "feedback",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "ix_feedback_status_created_at",
                table: "feedback",
                columns: new[] { "status", "created_at" });

            migrationBuilder.CreateIndex(
                name: "ix_agent_runs_target_type_target_id",
                table: "agent_runs",
                columns: new[] { "target_type", "target_id" });

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
                name: "ix_crash_events_received_at",
                table: "crash_events",
                column: "received_at");

            migrationBuilder.CreateIndex(
                name: "ix_crash_events_signature_received_at",
                table: "crash_events",
                columns: new[] { "signature", "received_at" });

            migrationBuilder.CreateIndex(
                name: "ix_crash_groups_status_last_seen_at",
                table: "crash_groups",
                columns: new[] { "status", "last_seen_at" });

            migrationBuilder.CreateIndex(
                name: "ix_inbox_items_user_id_created_at",
                table: "inbox_items",
                columns: new[] { "user_id", "created_at" });

            migrationBuilder.CreateIndex(
                name: "ix_reply_drafts_feedback_id",
                table: "reply_drafts",
                column: "feedback_id");

            migrationBuilder.CreateIndex(
                name: "ix_reply_drafts_status_created_at",
                table: "reply_drafts",
                columns: new[] { "status", "created_at" });

            migrationBuilder.CreateIndex(
                name: "ix_report_contacts_crash_event_id",
                table: "report_contacts",
                column: "crash_event_id",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "ix_report_contacts_expires_at",
                table: "report_contacts",
                column: "expires_at");

            migrationBuilder.CreateIndex(
                name: "ix_report_contacts_feedback_id",
                table: "report_contacts",
                column: "feedback_id",
                unique: true);
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
                name: "inbox_items");

            migrationBuilder.DropTable(
                name: "reply_drafts");

            migrationBuilder.DropTable(
                name: "report_contacts");

            migrationBuilder.DropTable(
                name: "crash_events");

            migrationBuilder.DropTable(
                name: "crash_groups");

            migrationBuilder.DropIndex(
                name: "ix_feedback_status_created_at",
                table: "feedback");

            migrationBuilder.DropColumn(
                name: "inbox_read_up_to",
                table: "users");

            migrationBuilder.DropColumn(
                name: "closed_at",
                table: "feedback");

            migrationBuilder.DropColumn(
                name: "crash_signature",
                table: "feedback");

            migrationBuilder.DropColumn(
                name: "device",
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
        }
    }
}
