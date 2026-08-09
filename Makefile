# Loom Stage Demo — one-command build.
#
#   make            -> toolchain + compile + dmg
#   make run        -> compile and launch the app (fast dev loop, no packaging)
#   make dmg        -> build dist/Loom Demo-1.0.0.dmg with bundled runtime
#   make verify     -> prove the jlinked runtime can run single-file .java sources
#   make clean      -> remove build output (keeps downloaded toolchain)
#   make distclean  -> remove everything including the ~250MB toolchain
#
# First run downloads JDK 21 + JavaFX + editor jars into tools/ and libs/
# (network needed once; every later build and the app itself are offline).
#
# Optional signing:  make dmg SIGN_IDENTITY="Developer ID Application: Your Name (TEAMID)"

ARCH_RAW := $(shell uname -m)
ifeq ($(ARCH_RAW),arm64)
ARCH := aarch64
else
ARCH := x64
endif

TOOLS      := tools
LIBS       := libs
JDK        := $(TOOLS)/jdk/Contents/Home
FX_VERSION := 22.0.2
FX_SDK     := $(TOOLS)/javafx-sdk-$(FX_VERSION)
FX_JMODS   := $(TOOLS)/javafx-jmods-$(FX_VERSION)

JAVAC    := $(JDK)/bin/javac
JAVA     := $(JDK)/bin/java
JAR      := $(JDK)/bin/jar
JLINK    := $(JDK)/bin/jlink
JPACKAGE := $(JDK)/bin/jpackage

JDK_URL := https://api.adoptium.net/v3/binary/latest/21/ga/mac/$(ARCH)/jdk/hotspot/normal/eclipse
FX_BASE := https://download2.gluonhq.com/openjfx/$(FX_VERSION)
MAVEN   := https://repo1.maven.org/maven2

# RichTextFX (editable code editor with syntax highlighting) + its dependencies.
DEP_PATHS := \
  org/fxmisc/richtext/richtextfx/0.11.6/richtextfx-0.11.6.jar \
  org/fxmisc/flowless/flowless/0.7.4/flowless-0.7.4.jar \
  org/fxmisc/undo/undofx/2.1.1/undofx-2.1.1.jar \
  org/fxmisc/wellbehaved/wellbehavedfx/0.3.3/wellbehavedfx-0.3.3.jar \
  org/reactfx/reactfx/2.0-M5/reactfx-2.0-M5.jar

DEP_JARS := $(addprefix $(LIBS)/,$(notdir $(DEP_PATHS)))

# Modules jlinked into the bundled runtime. jdk.compiler + jdk.zipfs are REQUIRED:
# the Thread Bomb demo launches `java Demo.java` (single-file source execution)
# from this runtime, which silently needs the compiler. Do not remove them.
RUNTIME_MODULES := java.base,java.desktop,java.logging,java.naming,java.net.http,jdk.httpserver,jdk.compiler,jdk.zipfs,jdk.unsupported,jdk.unsupported.desktop,javafx.base,javafx.graphics,javafx.controls

APP_NAME    := Loom Demo
APP_VERSION := 1.0.0
MAIN_CLASS  := loomdemo.Main

SRC := $(shell find src -name '*.java')

.PHONY: all toolchain build run jar runtime verify dmg clean distclean

all: dmg

# ---------------------------------------------------------------- toolchain

toolchain: $(JAVAC) $(FX_SDK)/lib/javafx.controls.jar $(FX_JMODS)/javafx.base.jmod $(DEP_JARS)

$(JAVAC):
	@echo "==> Downloading Temurin JDK 21 ($(ARCH))..."
	@mkdir -p $(TOOLS)
	curl -fL --progress-bar -o $(TOOLS)/jdk.tar.gz "$(JDK_URL)"
	@rm -rf $(TOOLS)/jdk-extract $(TOOLS)/jdk && mkdir -p $(TOOLS)/jdk-extract
	tar xzf $(TOOLS)/jdk.tar.gz -C $(TOOLS)/jdk-extract
	mv $(TOOLS)/jdk-extract/jdk-* $(TOOLS)/jdk
	@rmdir $(TOOLS)/jdk-extract && rm -f $(TOOLS)/jdk.tar.gz
	@$(JAVA) -version

$(FX_SDK)/lib/javafx.controls.jar:
	@echo "==> Downloading JavaFX $(FX_VERSION) SDK..."
	@mkdir -p $(TOOLS)
	curl -fL --progress-bar -o $(TOOLS)/fx-sdk.zip "$(FX_BASE)/openjfx-$(FX_VERSION)_osx-$(ARCH)_bin-sdk.zip"
	unzip -qo $(TOOLS)/fx-sdk.zip -d $(TOOLS)
	@rm -f $(TOOLS)/fx-sdk.zip

$(FX_JMODS)/javafx.base.jmod:
	@echo "==> Downloading JavaFX $(FX_VERSION) jmods..."
	@mkdir -p $(TOOLS)
	curl -fL --progress-bar -o $(TOOLS)/fx-jmods.zip "$(FX_BASE)/openjfx-$(FX_VERSION)_osx-$(ARCH)_bin-jmods.zip"
	unzip -qo $(TOOLS)/fx-jmods.zip -d $(TOOLS)
	@rm -f $(TOOLS)/fx-jmods.zip

$(LIBS)/%.jar:
	@mkdir -p $(LIBS)
	@for p in $(DEP_PATHS); do \
	  case $$p in */$(notdir $@)) \
	    echo "==> Downloading $(notdir $@)..."; \
	    curl -fL -sS -o "$@" "$(MAVEN)/$$p" ;; \
	  esac; \
	done

# ---------------------------------------------------------------- compile & run

build: toolchain $(SRC)
	@mkdir -p out
	$(JAVAC) --release 21 --module-path $(FX_SDK)/lib --add-modules javafx.controls \
	  -cp "$(LIBS)/*" -d out $(SRC)
	@find src -name '*.css' | while read f; do \
	  dest=out$${f#src}; mkdir -p $$(dirname $$dest); cp $$f $$dest; done

run: build
	$(JAVA) --module-path $(FX_SDK)/lib --add-modules javafx.controls \
	  -cp "out:$(LIBS)/*" $(MAIN_CLASS)

# ---------------------------------------------------------------- package

jar: build
	@rm -rf build/fat build/app && mkdir -p build/fat build/app
	@set -e; cd build/fat; for j in $(abspath $(DEP_JARS)); do "$(abspath $(JAR))" xf $$j; done
	@rm -rf build/fat/META-INF build/fat/module-info.class
	@cp -R out/. build/fat/
	"$(JAR)" cfe build/app/app.jar $(MAIN_CLASS) -C build/fat .

runtime: toolchain
	@rm -rf build/runtime && mkdir -p build
	@echo "==> jlinking bundled runtime (includes jdk.compiler for 'java Demo.java')..."
	$(JLINK) --module-path "$(JDK)/jmods:$(FX_JMODS)" \
	  --add-modules $(RUNTIME_MODULES) \
	  --strip-debug --no-header-files --no-man-pages --compress zip-6 \
	  --output build/runtime

verify: runtime
	@printf 'public class Hello { public static void main(String[] a) { System.out.println("source-launcher-ok on " + Runtime.version()); } }\n' > build/Hello.java
	@cd build && ./runtime/bin/java Hello.java
	@echo "==> Bundled runtime can run single-file .java sources. Thread Bomb will work when packaged."
# Threads 101 precompiles snippets in-process so re-running is instant. That needs the
# javac ToolProvider, which only exists if jdk.compiler survived jlink. `make run` uses
# the full JDK and would never catch its absence — this does, before the dmg ships.
	@printf 'import java.util.spi.ToolProvider;\npublic class Tool { public static void main(String[] a) throws Exception {\n  var javac = ToolProvider.findFirst("javac").orElseThrow(() -> new IllegalStateException("no javac ToolProvider in the bundled runtime"));\n  java.nio.file.Path d = java.nio.file.Files.createTempDirectory("verify-");\n  java.nio.file.Path s = d.resolve("Tiny.java");\n  java.nio.file.Files.writeString(s, "public class Tiny { public static void main(String[] a) {} }");\n  int rc = javac.run(System.out, System.err, "-d", d.toString(), s.toString());\n  if (rc != 0 || !java.nio.file.Files.exists(d.resolve("Tiny.class"))) throw new IllegalStateException("javac ToolProvider failed, rc=" + rc);\n  System.out.println("tool-provider-javac-ok");\n} }\n' > build/Tool.java
	@cd build && ./runtime/bin/java Tool.java
	@echo "==> Bundled runtime can compile in-process. Threads 101 fast re-runs will work when packaged."

dmg: jar runtime verify
	@rm -rf dist
	$(JPACKAGE) --type dmg --name "$(APP_NAME)" \
	  --input build/app --main-jar app.jar --main-class $(MAIN_CLASS) \
	  --runtime-image build/runtime \
	  --java-options "--add-modules=javafx.controls" \
	  --java-options "-Xmx1g" \
	  --app-version $(APP_VERSION) \
	  --mac-package-identifier dev.loomdemo.app \
	  $(if $(SIGN_IDENTITY),--mac-sign --mac-signing-key-user-name "$(SIGN_IDENTITY)") \
	  --dest dist
	@echo "==> Built: dist/$(APP_NAME)-$(APP_VERSION).dmg"

clean:
	rm -rf out build dist

distclean: clean
	rm -rf $(TOOLS) $(LIBS)
