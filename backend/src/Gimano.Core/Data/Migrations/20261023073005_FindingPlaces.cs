using Microsoft.EntityFrameworkCore.Migrations;
using NetTopologySuite.Geometries;

#nullable disable

namespace Gimano.Core.Data.Migrations
{
    /// <inheritdoc />
    public partial class FindingPlaces : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "subject",
                table: "inbox_items",
                type: "character varying(200)",
                maxLength: 200,
                nullable: true);

            migrationBuilder.CreateTable(
                name: "search_misses",
                columns: table => new
                {
                    query = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: false),
                    cell = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: false),
                    week = table.Column<string>(type: "character varying(8)", maxLength: 8, nullable: false),
                    count = table.Column<int>(type: "integer", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_search_misses", x => new { x.week, x.cell, x.query });
                });

            migrationBuilder.CreateTable(
                name: "street_addresses",
                columns: table => new
                {
                    id = table.Column<string>(type: "character varying(24)", maxLength: 24, nullable: false),
                    house_number = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: false),
                    street = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    postcode = table.Column<string>(type: "character varying(12)", maxLength: 12, nullable: true),
                    city = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: true),
                    country = table.Column<string>(type: "character varying(2)", maxLength: 2, nullable: false),
                    location = table.Column<Point>(type: "geography (point, 4326)", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_street_addresses", x => x.id);
                });

            migrationBuilder.CreateIndex(
                name: "ix_street_addresses_location",
                table: "street_addresses",
                column: "location")
                .Annotation("Npgsql:IndexMethod", "gist");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "search_misses");

            migrationBuilder.DropTable(
                name: "street_addresses");

            migrationBuilder.DropColumn(
                name: "subject",
                table: "inbox_items");
        }
    }
}
