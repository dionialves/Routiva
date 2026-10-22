FROM golang:1.24.9-alpine AS build
WORKDIR /build
COPY collector/go.mod collector/go.sum ./
RUN go mod download
COPY collector/ ./
RUN go test ./... && CGO_ENABLED=0 go build -trimpath -o /collector ./cmd/collector
FROM alpine:3.22
RUN addgroup -g 10001 routiva && adduser -D -u 10001 -G routiva routiva && mkdir /run/routiva && chown routiva:routiva /run/routiva
COPY --from=build /collector /usr/local/bin/routiva-collector
USER 10001:10001
HEALTHCHECK --interval=10s --timeout=3s CMD ["/usr/local/bin/routiva-collector", "--health"]
ENTRYPOINT ["/usr/local/bin/routiva-collector"]
