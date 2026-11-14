# Reference web deployment (#536): the Expo static export served by nginx with the security
# headers of deploy/nginx/nginx.conf. Build context: mobile/ (see docker-compose.yml, profile web).
# Put the published port behind an HTTPS reverse proxy - see mobile/README.md, "Web app".

FROM node:22-bookworm-slim AS export
WORKDIR /build
# The server address the sign-in form suggests; members can enter another one (FR-CLI-007).
ARG EXPO_PUBLIC_API_URL
ENV EXPO_PUBLIC_API_URL=${EXPO_PUBLIC_API_URL} CI=1 EXPO_NO_TELEMETRY=1
# Node 22 ships npm 10, the version Mobile Web CI installs the lockfile with.
COPY package.json package-lock.json .npmrc ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx expo export -p web

FROM nginx:1.27-alpine
# write-csp.py hashes the export's inline scripts and styles when the container starts.
RUN apk add --no-cache python3
WORKDIR /srv/web
COPY deploy/nginx/nginx.conf deploy/nginx/write-csp.py ./
COPY deploy/nginx/docker-entrypoint.sh /docker-entrypoint.d/40-trackmywealth-csp.sh
COPY --from=export /build/dist ./html
# In a container the listener must accept connections from the published port; publish it on the
# host's loopback (docker-compose.yml) so only the reverse proxy reaches it.
RUN sed -i 's/listen 127.0.0.1:8082;/listen 8082;/' nginx.conf \
    && grep -q '^        listen 8082;$' nginx.conf \
    && cp /etc/nginx/mime.types . \
    && chown -R nginx:nginx /srv/web \
    && chmod +x /docker-entrypoint.d/40-trackmywealth-csp.sh
USER nginx
EXPOSE 8082
CMD ["nginx", "-p", "/srv/web/", "-c", "/srv/web/nginx.conf", "-g", "daemon off;"]
