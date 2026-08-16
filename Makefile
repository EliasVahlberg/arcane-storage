# Thin wrapper so the Necesse mod build behaves like cargo/cmake:
HARNESS_VENV = $(CURDIR)/../necesse-headless-harness/.venv/bin/python
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

.PHONY: help build test scene run dev server appid textures clean stop tasks doctor

help: ## Show available targets
	@grep -hE '^[a-z-]+:.*##' $(MAKEFILE_LIST) \
		| sed 's/:.*##/\t/' | expand -t20

build: ## Compile the mod and produce build/jar/<name>.jar
	@mkdir -p $(LOGDIR)
	@time $(GRADLE) buildModJar < /dev/null 2>&1 | tee $(LOGDIR)/build.log
	@ls -la build/jar/
	@$(MAKE) --no-print-directory releasecheck

releasecheck: ## Assert the one shipped jar is safe for players (runs as part of 'make build')
	@# Three invariants, and each one has been broken at some point without announcing itself, because none of them is
	@# visible from a machine where the harness is always installed.
	@#
	@# The fatal one is the supertype rule. A shipped class that implements a harness interface cannot be defined when
	@# the harness is absent, and the mod loader turns that into a refusal to load the mod at all. A harness type named
	@# in a method body is fine -- that resolves lazily -- so this checks declarations only, which is why it greps for
	@# candidates and then reads each one's declaration line rather than trusting the grep.
	@jar=$$(ls build/jar/*.jar); \
	fail=0; \
	n=$$(unzip -l "$$jar" | grep -cE "arcanestorage/harness/.*\.class$$" || true); \
	if [ "$$n" != "0" ]; then echo "  FAIL: $$n harness class(es) ship as code, not as bridge resources"; fail=1; \
	else echo "  ok: no harness classes ship as code"; fi; \
	n=$$(unzip -l "$$jar" | grep -c "harnessbridge/.*\.classdata" || true); \
	if [ "$$n" = "0" ]; then echo "  FAIL: the harness bridge resources are missing, so debugging is impossible"; fail=1; \
	else echo "  ok: $$n bridge resources present"; fi; \
	if ! unzip -l "$$jar" | grep -q "harnessbridge/bridge.txt"; then \
	  echo "  FAIL: bridge.txt is missing, so the harness cannot find the entry class"; fail=1; \
	else echo "  ok: bridge.txt present"; fi; \
	if unzip -p "$$jar" mod.info | grep -q "necesseheadlessharness"; then \
	  echo "  FAIL: mod.info names the harness"; fail=1; \
	else echo "  ok: mod.info does not name the harness"; fi; \
	rm -rf build/relcheck && mkdir -p build/relcheck && (cd build/relcheck && unzip -q -o "../../$$jar"); \
	bad=$$(cd build/relcheck && for c in $$(grep -rla "necesseheadlessharness" --include="*.class" . 2>/dev/null); do \
	  javap -p "$$c" 2>/dev/null | grep -m1 -E "(class|interface|enum) " | grep -q "necesseheadlessharness" && echo "$$c"; \
	done); \
	if [ -n "$$bad" ]; then echo "  FAIL: these ship as code and inherit a harness type:"; echo "$$bad" | sed 's/^/    /'; fail=1; \
	else echo "  ok: no shipped class inherits a harness type"; fi; \
	rm -rf build/relcheck; \
	if [ "$$fail" != "0" ]; then echo "  this jar is not shippable"; exit 1; fi

testjar: ## Deprecated: there is one jar now, and the scenarios use it. Builds it, for anything still calling this.
	@echo "note: there is only one jar now -- the bridge ships as harnessbridge/**.classdata resources"
	@$(MAKE) --no-print-directory build

test: ## Run the unit tests (game-independent logic only; no game or Steam needed)
	@mkdir -p $(LOGDIR)
	$(GRADLE) test < /dev/null 2>&1 | tee $(LOGDIR)/test.log

scene: ## Build a state to look at, headlessly: make scene FILE=tests/scenes/full_network.txt
	@test -n "$(FILE)" || { echo "usage: make scene FILE=tests/scenes/<name>.txt"; exit 2; }
	@$(MAKE) --no-print-directory build > /dev/null
	@tools/run_scenario.sh --scene "$(FILE)"

run: ## Launch the game with the in-development mod (needs Steam running). PACKETLOG=1 logs packets, HARNESS=1 enables /harness in-game
	@mkdir -p $(LOGDIR)
	$(GRADLE) runClient $(if $(PACKETLOG),-Ppacketlog,) $(if $(HARNESS),-Pharness,) < /dev/null 2>&1 | tee $(LOGDIR)/runClient.log

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

pytest: ## Run the fast Python suite: everything except the slow tier (the one to run while working)
	@$(MAKE) --no-print-directory build > /dev/null
	@# The venv lives in the harness repo, because the client is released with the jar.
	@# -m 'not slow' drops the three server restarts and the largest-network budget check. What is
	@# left is every correctness test, which is what a change needs to be judged against.
	@$(HARNESS_VENV) -m pytest tests/python -q -m 'not slow'

pytest-all: ## Run the whole Python suite including the slow tier (before a push or a release)
	@$(MAKE) --no-print-directory build > /dev/null
	@$(HARNESS_VENV) -m pytest tests/python -q

pytest-clock: ## Run the whole suite on the game's own clock: the control for detached ticks
	@$(MAKE) --no-print-directory build > /dev/null
	@# The suite detaches game time from the wall clock by default, granting ticks instead of waiting for
	@# them -- 20s against 333s. This target is the control: it runs the world on its own clock, so
	@# something that passes here and fails by default means detached ticks are involved. Takes minutes.
	@$(HARNESS_VENV) -m pytest tests/python -q --clock-ticks
