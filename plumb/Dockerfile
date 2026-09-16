###########################################################
# Build stage
# Context: plumb/ (one Maven module, no siblings to copy)
###########################################################
FROM ghcr.io/altirllc/haven-base-jvm-build:25 AS builder
WORKDIR /build
# Maven assumes it owns the machine and will OOM in a constrained builder.
# Pin the heap so the build fits a small runner.
ENV MAVEN_OPTS="-Xmx1g"
ENV MAVEN_ARGS="-B -ntp"
# Build config first: it changes far less often than source, so the dependency
# download stays a cached layer across code edits.
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
RUN ./mvnw -q dependency:go-offline || true
COPY src src
# -DskipTests: CI has already run them, and they are the same tests either way.
RUN ./mvnw -q -DskipTests package

###########################################################
# Runtime stage — stock JRE, nonroot
#
# Not jlink and not GraalVM native, for the reason the agent scaffold documents
# at length: both trim the runtime by assuming they know which classes load, and
# both are wrong in ways that only appear at runtime. plumb has a second reason
# to refuse them — it is the thing you run to find out what is broken, so it is
# the last image in the fleet that should carry a novel failure mode of its own.
###########################################################
FROM ghcr.io/altirllc/haven-base-jvm:25 AS runtime
COPY --from=builder --chown=1001:1001 /build/target/*.jar /app/app.jar
EXPOSE 3000
# MaxRAMPercentage sizes the heap off the container limit; without it the JVM
# assumes it owns the node. The bundle sets that limit.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-XX:MaxRAMPercentage=75", "-XX:+UseSerialGC", "-jar", "/app/app.jar"]
