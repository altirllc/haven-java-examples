###########################################################
# Build Stage
# Context: backend/ (all modules + the Maven build)
###########################################################
FROM ghcr.io/altirllc/haven-base-jvm-build:25 AS builder
WORKDIR /build
# Maven assumes it owns the machine and will OOM in a constrained builder.
# Pin the heap so the build fits a small runner.
ENV MAVEN_OPTS="-Xmx1g"
ENV MAVEN_ARGS="-B -ntp"
# Build config first: it changes far less often than source, so the dependency
# download stays cached across code edits.
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
COPY domain/pom.xml domain/
COPY contracts/pom.xml contracts/
COPY workflows/pom.xml workflows/
COPY orchestration/pom.xml orchestration/
COPY persistence/pom.xml persistence/
COPY agent/pom.xml agent/
COPY api/pom.xml api/
COPY jobs/pom.xml jobs/
RUN ./mvnw -q -pl api -am dependency:go-offline || true
COPY domain domain
COPY contracts contracts
COPY workflows workflows
COPY orchestration orchestration
COPY persistence persistence
COPY agent agent
COPY api api
# -DskipTests: the integration tests need Docker (Testcontainers) and CI has
# already run them. A build inside a build cannot start containers.
RUN ./mvnw -q -pl api -am -DskipTests package

###########################################################
# Runtime Stage — stock JRE, nonroot
#
# NOT jlink, and NOT GraalVM native — both for the same reason. Each trims the
# runtime by assuming it knows which classes load, and each is wrong in a way
# you only discover at RUNTIME: this app's own dependencies already needed
# modules like java.desktop (java.beans, via Spring's binder) that nothing
# statically references. Every engineer who adds a library would inherit that
# debugging.
#
# The arithmetic also does not justify it: the dependencies are ~162MB on their
# own (openai-java-core alone is 40MB, temporal-serviceclient 12MB), so trimming
# the JRE buys ~110MB against a permanent tax on everyone who extends this.
# Smallest image SUBJECT TO best practice — size never wins that trade.
#
# The layered jar is where the real win is: dependencies change rarely, so they
# stay a cached layer while application code re-layers on every build.
###########################################################
FROM ghcr.io/altirllc/haven-base-jvm:25 AS runtime
COPY --from=builder --chown=1001:1001 /build/api/target/*.jar /app/app.jar
EXPOSE 3000 3001
# MaxRAMPercentage sizes the heap off the container limit; without it the JVM
# assumes it owns the node. The bundle sets that limit.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-XX:MaxRAMPercentage=75", "-XX:+UseSerialGC", "-jar", "/app/app.jar"]
