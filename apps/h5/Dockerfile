# syntax=docker/dockerfile:1

# ---- Stage 1: build the static bundle ----
FROM node:22-alpine AS build
WORKDIR /app

# Install dependencies (prefer a reproducible ci install when a lockfile is present).
COPY package*.json ./
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi

# Build the app.
COPY . .
RUN npm run build

# ---- Stage 2: serve the static bundle with nginx ----
FROM nginx:1.27-alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
