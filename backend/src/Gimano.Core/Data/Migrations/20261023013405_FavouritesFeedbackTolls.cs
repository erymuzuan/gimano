using System;
using System.Collections.Generic;
using Microsoft.EntityFrameworkCore.Migrations;
using Npgsql.EntityFrameworkCore.PostgreSQL.Metadata;

#nullable disable

namespace Gimano.Core.Data.Migrations
{
    /// <inheritdoc />
    public partial class FavouritesFeedbackTolls : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "icon",
                table: "saved_places",
                type: "character varying(12)",
                maxLength: 12,
                nullable: false,
                defaultValue: "Star");

            migrationBuilder.AddColumn<int>(
                name: "sort_order",
                table: "saved_places",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            // Existing home/work rows get their fixed icons.
            migrationBuilder.Sql("UPDATE saved_places SET icon = kind WHERE kind IN ('Home', 'Work');");

            migrationBuilder.CreateTable(
                name: "feedback",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<Guid>(type: "uuid", nullable: false),
                    category = table.Column<string>(type: "character varying(12)", maxLength: 12, nullable: false),
                    message = table.Column<string>(type: "character varying(2000)", maxLength: 2000, nullable: false),
                    rating = table.Column<int>(type: "integer", nullable: true),
                    screen = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: true),
                    screenshot = table.Column<byte[]>(type: "bytea", nullable: true),
                    screenshot_content_type = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: true),
                    status = table.Column<string>(type: "character varying(12)", maxLength: 12, nullable: false),
                    external_url = table.Column<string>(type: "character varying(300)", maxLength: 300, nullable: true),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    context = table.Column<string>(type: "jsonb", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_feedback", x => x.id);
                    table.ForeignKey(
                        name: "fk_feedback_users_user_id",
                        column: x => x.user_id,
                        principalTable: "users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "toll_rates",
                columns: table => new
                {
                    id = table.Column<int>(type: "integer", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    plaza_osm_id = table.Column<string>(type: "text", nullable: true),
                    name = table.Column<string>(type: "text", nullable: false),
                    highway = table.Column<string>(type: "text", nullable: true),
                    lat = table.Column<double>(type: "double precision", nullable: true),
                    lng = table.Column<double>(type: "double precision", nullable: true),
                    system = table.Column<string>(type: "character varying(12)", maxLength: 12, nullable: false),
                    entry_plaza = table.Column<string>(type: "text", nullable: true),
                    class0myr = table.Column<decimal>(type: "numeric(8,2)", precision: 8, scale: 2, nullable: true),
                    class1myr = table.Column<decimal>(type: "numeric(8,2)", precision: 8, scale: 2, nullable: true),
                    effective_date = table.Column<DateOnly>(type: "date", nullable: true),
                    source_url = table.Column<string>(type: "text", nullable: true),
                    notes = table.Column<string>(type: "text", nullable: true),
                    matched_place_ids = table.Column<List<string>>(type: "text[]", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_toll_rates", x => x.id);
                });

            migrationBuilder.CreateIndex(
                name: "ix_feedback_user_id_created_at",
                table: "feedback",
                columns: new[] { "user_id", "created_at" });

            migrationBuilder.CreateIndex(
                name: "ix_toll_rates_matched_place_ids",
                table: "toll_rates",
                column: "matched_place_ids")
                .Annotation("Npgsql:IndexMethod", "gin");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "feedback");

            migrationBuilder.DropTable(
                name: "toll_rates");

            migrationBuilder.DropColumn(
                name: "icon",
                table: "saved_places");

            migrationBuilder.DropColumn(
                name: "sort_order",
                table: "saved_places");
        }
    }
}
