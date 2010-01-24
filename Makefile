# @code standards ignore file

SHELL = /bin/bash

VERSION = 1.4.0

OPT_BUILDR = $(shell (which buildr >/dev/null && which buildr) || which mvn)

help:
	@echo "NOTE - this makefile is mostly unix aliases. Use 'mvn install' to build."
	@grep -iE "^(###.+|[a-zA-Z0-9_-]+:.*(#.+)?)$$" Makefile | sed -u -r "/ #HIDDEN/d; s/^### /\n/g; s/:.+#/#/g; s/^/    /g; s/#/\\n        /g; s/:$$//g"

help-all: # show uncommon commands as well
	@echo "NOTE - this makefile is mostly unix aliases. Use 'mvn install' to build."
	@grep -iE "^(###.+|[a-zA-Z0-9_-]+:.*(#.+)?)$$" Makefile | sed -u -r "s/ #HIDDEN//g; s/^### /\n/g; s/:.+#/#/g; s/^/    /g; s/#/\\n        /g; s/:$$//g"

show-info:
	@echo "version = $(VERSION)"
	@echo "buildr or maven = $(OPT_BUILDR)"

clean:
	zsh -c "setopt -G; rm -f **/*timestamp **/*pyc **/*~ **/skalch/plugins/type_graph.gxl"
	zsh -c "setopt -G; rm -rf **/(bin|target) .gen **/gen/ **/reports/junit"

compile: # build all sources with buildr (if found) or maven
	$(OPT_BUILDR) compile

maven-install: compile
	mvn install -Dmaven.test.skip=true

### development #HIDDEN

codegen: # codegen a few files (not very high probability of changing) #HIDDEN
	python scripts/run_jinja2.py

renamer-script: #HIDDEN
	[ -f sketch-noarch.jar ] || { make assemble-noarch; cp target/sketch-*-noarch.jar sketch-noarch.jar; }
	python scripts/rewrite_fcns.py

### distribution

assemble-file: # internal step
	cp $(FILE) tmp-assembly.xml
	mvn -e assembly:assembly -Dsketch-backend-proj=../sketch-backend -Dmaven.test.skip=true
	rm tmp-assembly.xml

assemble-noarch:
	make assemble-file FILE=jar_assembly.xml

assemble-arch:
	make assemble-file FILE=platform_jar_assembly.xml
	make assemble-file FILE=launchers_assembly.xml
	make assemble-file FILE=tar_src_assembly.xml

assemble: # build all related jar files, assuming sketch-backend is at ../sketch-backend
	mvn -e -q clean compile
	make assemble-noarch assemble-arch
	chmod 755 target/sketch-*-launchers.dir/dist/*/install
	cd target; tar cf sketch-$(VERSION).tar sketch-*-all-*.jar

dist: assemble # alias for assemble

win-installer: assemble-arch
	basedir=$$(pwd); cd target/*-launchers-windows.dir/dist/*; mv COPYING *jar installer; cd installer; /cygdrive/c/Program\ Files\ \(x86\)/NSIS/makensis sketch-installer.nsi; cp *exe "$$basedir"
	@ls -l *exe

deploy: compile
	mvn deploy -Dmaven.test.skip=true

osc: assemble-noarch
	mkdir -p "java-build"; cp target/sketch-$(VERSION)-noarch.jar java-build
	python ../sketch-backend/distconfig/linux_rpm/build.py --name sketch-frontend --additional_path java-build --version $(VERSION) --no --osc --commit_msg "[incremental]"
	rm -rf java-build

system-install: assemble-arch # usage: make system-install DESTDIR=/usr/bin
	[ "$(DESTDIR)" ] || { echo "no destination directory defined. try make help."; exit 1; }
	mkdir -p $(DESTDIR)
	DESTDIR="$$(readlink -f "$(DESTDIR)")"; cd target/sketch-*-launchers.dir/dist/* && install -m 644 *jar "$$DESTDIR" && install -m 755 sketch psketch stensk "$$DESTDIR"

### testing

test:
	mvn test | tee target/test_output.txt | grep -E "\[SKETCH\] running test|\[ERROR\]"

test-seq:
	set -o pipefail; mvn test "-Dtest=SequentialJunitTest" | tee target/test_output.txt | grep -E "\[SKETCH\] running test|\[ERROR\]"

test-par:
	set -o pipefail; mvn test "-Dtest=ParallelJunitTest" | tee target/test_output.txt | grep -E "\[SKETCH\] running test|\[ERROR\]"

test-sten:
	set -o pipefail; mvn test "-Dtest=StencilJunitTest" | tee target/test_output.txt | grep -E "\[SKETCH\] running test|\[ERROR\]"

test-release-benchmarks:
	for i in src/release_benchmarks/sk/*.sk; do make run-local-seq EXEC_ARGS="$$i"; done | tee target/test_output.txt | grep -E "Benchmark = src/release_benchmarks|\[ERROR\]"

### manually running sketches using the development versions; use $(make <target> EXEC_ARGS=args)

run-platform-seq: # run a test using the platform jar
	[ -f target/sketch-*-all-*.jar ] || make assemble
	java -cp target/sketch-*-all-*.jar -ea sketch.compiler.main.seq.SequentialSketchMain $(EXEC_ARGS)

run-local-seq:
	mvn -e compile exec:java "-Dexec.mainClass=sketch.compiler.main.seq.SequentialSketchMain" "-Dexec.args=$(EXEC_ARGS)"

dump-fcn-info: # dump information about functions to a file. usage: EXEC_ARGS=filename.sk
	mvn -e compile exec:java "-Dexec.mainClass=sketch.compiler.main.other.ParseFunctions" "-Dexec.args=$(EXEC_ARGS)"

run-local-par:
	mvn -e compile exec:java "-Dexec.mainClass=sketch.compiler.main.par.ParallelSketchMain" "-Dexec.args=$(EXEC_ARGS)"

run-local-sten:
	mvn -e compile exec:java "-Dexec.mainClass=sketch.compiler.main.sten.StencilSketchMain" "-Dexec.args=$(EXEC_ARGS)"
