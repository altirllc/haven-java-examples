###########################################################
# Build Stage
# Context: ui/ (NOT the repo root)
#
# The TypeScript templates build the UI from the repo root because their root is
# an npm workspace holding the lock file. The Maven root has no package.json, so
# here the UI is a standalone npm project and its lock file lives in this
# directory. Build it with:  docker build -f ui/Dockerfile -t loom-ui ui/
###########################################################
FROM ghcr.io/altirllc/haven-base-node:22 AS builder
USER root
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci --quiet --no-audit --no-fund
COPY . ./
RUN npm run build

###########################################################
# Runtime Stage — nginx serves the static SPA
###########################################################
FROM ghcr.io/altirllc/haven-base-nginx:stable
COPY --from=builder /build/dist /usr/share/nginx/html
