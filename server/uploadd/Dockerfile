# Static build (pure-Go SQLite driver, CGO off) -> minimal runtime image.
FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /uploadd .

FROM gcr.io/distroless/static-debian12
COPY --from=build /uploadd /uploadd
# No ports EXPOSEd deliberately: this service must only ever be reached
# over the compose-internal bridge (SERVER_SPEC §1.2 rule 6).
ENTRYPOINT ["/uploadd"]
