# Thin wrapper so the Necesse mod build behaves like cargo/cmake:
# one short command, output streams live, exit status is real, never hangs.
#
# Three rules encoded here, each fixing a specific failure we hit:
#   1. `< /dev/null`      - a backgrounded/orphaned gradlew that touches the
#                           controlling terminal gets SIGTTIN/SIGTTOU and is
#                           STOPPED by the kernel. It then looks identical to a
#                           hang and sits until the timeout. This is the bug
#                           that cost us 20 minutes.
#   2. `--console=plain`  - no ANSI progress redraws, so piping and logging work.
#   3. `pipefail` + tee   - live output AND a log file, without tee masking a
#                           non-zero gradle exit status.
#
# Never pipe gradlew through `tail`/`head`: it buffers everything until exit,
# which makes a working build indistinguishable from a stuck one.

SHELL := /bin/bash
.SHELLFLAGS := -o pipefail -c

GRADLE := ./gradlew --console=plain
LOGDIR := build/logs

.PHONY: persistence help build test scenario scenarios run dev server appid textures clean stop tasks doctor

help: ## Show available targets
	@grep -hE '^[a-z-]+:.*##' $(MAKEFILE_LIST) \
		| sed 's/:.*##/\t/' | expand -t20

build: ## Compile the mod and produce build/jar/<name>.jar
	@mkdir -p $(LOGDIR)
	@time $(GRADLE) buildModJar < /dev/null 2>&1 | tee $(LOGDIR)/build.log
	@ls -la build/jar/

test: ## Run the unit tests (game-independent logic only; no game or Steam needed)
	@mkdir -p $(LOGDIR)
	$(GRADLE) test < /dev/null 2>&1 | tee $(LOGDIR)/test.log

scenario: ## Run one scenario against a headless server: make scenario FILE=tests/scenarios/x.txt
	@test -n "$(FILE)" || { echo "usage: make scenario FILE=tests/scenarios/<name>.txt"; exit 2; }
	@$(MAKE) --no-print-directory build > /dev/null
	@tools/run_scenario.sh "$(FILE)"

scenarios: ## Run every scenario, plus persistence across a restart; non-zero exit on any failure
	@$(MAKE) --no-print-directory build > /dev/null
	@# The persistence write phase runs last in this boot so nothing resets its objects, and
	@# the shutdown save puts it on disk for the second boot to verify.
	@tools/run_scenario.sh tests/scenarios/*.txt tests/scenarios/persistence/write.txt
	@tools/run_scenario.sh --keep tests/scenarios/persistence/verify.txt

persistence: ## Just the persistence pair: one boot writes, a second verifies after a restart
	@$(MAKE) --no-print-directory build > /dev/null
	@tools/run_scenario.sh tests/scenarios/persistence/write.txt
	@tools/run_scenario.sh --keep tests/scenarios/persistence/verify.txt

run: ## Launch the game with the in-development mod (needs Steam running). PACKETLOG=1 logs inbound packets
	@mkdir -p $(LOGDIR)
	$(GRADLE) runClient $(if $(PACKETLOG),-Ppacketlog,) < /dev/null 2>&1 | tee $(LOGDIR)/runClient.log

dev: ## Launch a second client with a different auth ID, for multiplayer testing
	@mkdir -p $(LOGDIR)
	$(GRADLE) runDevClient < /dev/null 2>&1 | tee $(LOGDIR)/runDevClient.log

server: ## Launch a dedicated server with the mod (tests resource-less loading)
	@mkdir -p $(LOGDIR)
	$(GRADLE) runServer < /dev/null 2>&1 | tee $(LOGDIR)/runServer.log

textures: ## Fix alpha-blended texture edges in resources/
	$(GRADLE) preAntialiasTextures < /dev/null 2>&1

clean: ## Remove build output
	$(GRADLE) clean < /dev/null 2>&1

stop: ## Stop the Gradle daemon (use when it misbehaves)
	$(GRADLE) --stop < /dev/null 2>&1

tasks: ## List the necesse-specific Gradle tasks
	@$(GRADLE) tasks --group necesse < /dev/null 2>&1

doctor: ## Verify the toolchain assumptions on this machine
	@echo "Gradle JVM  : $$(grep -oP '(?<=^org.gradle.java.home=).*' gradle.properties)"
	@echo "Game dir    : $$(grep -oP '(?<=^necesseGameDir=).*' gradle.properties)"
	@test -f "$$(grep -oP '(?<=^necesseGameDir=).*' gradle.properties)/Necesse.jar" \
		&& echo "Necesse.jar : found" || echo "Necesse.jar : MISSING"
	@echo "Local mods  : $$HOME/.config/Necesse/mods"
	@pgrep -x steam >/dev/null && echo "Steam       : running" || echo "Steam       : NOT running (runClient will fail)"
	@# A headless JDK has no libawt_xawt.so, which forces GraphicsEnvironment.isHeadless()
	@# true and makes every Swing window throw. The game client is GLFW so it survives,
	@# but runServer's ServerJFrame and all error/notice dialogs do not.
	@test -f "$$(grep -oP '(?<=^org.gradle.java.home=).*' gradle.properties)/lib/libawt_xawt.so" \
		&& echo "AWT         : headful" \
		|| echo "AWT         : HEADLESS JVM (runServer cannot work; error dialogs throw instead of showing)"
