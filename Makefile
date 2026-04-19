# STIMBA VNC URCap — developer shortcuts
# ========================================
#
# `make` on its own: full build + regression gate.
# Individual targets documented below.

.PHONY: help build regress regress-write clean

help:
	@echo "STIMBA VNC URCap targets:"
	@echo "  make build          — mvn package via Docker (produces target/*.urcap)"
	@echo "  make regress        — unpack latest .urcap and diff against wiki baseline"
	@echo "  make regress-write  — regenerate wiki/public-api-baseline.txt from latest"
	@echo "                        .urcap (use ONLY after intentional API change)"
	@echo "  make clean          — remove target/ + stray tmpdirs"
	@echo ""
	@echo "Default: build && regress — fails CI on stub drift."

build:
	cd build-with-docker && docker compose run --rm build

# Gate. Run after every build. Fails with exit 2 on signature drift.
regress:
	@./build-with-docker/regress.sh

# Promote a new API surface to baseline. Do NOT add this to CI.
regress-write:
	@./build-with-docker/regress.sh --write
	@echo ""
	@echo "Review the diff and commit wiki/public-api-baseline.txt:"
	@echo "  git diff wiki/public-api-baseline.txt"

# Default target: build + regress. If this passes, .urcap is shippable.
all: build regress

clean:
	rm -rf target/
	rm -rf /tmp/urcap-regress-*
