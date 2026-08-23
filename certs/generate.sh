#!/bin/sh
set -e
cd /certs

if [ -f ca.crt ] && [ -f server.crt ] && [ -f server.key ]; then
  echo "TLS certs already present"
  exit 0
fi

apk add --no-cache openssl >/dev/null

openssl genrsa -out ca.key 2048
openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 -out ca.crt \
  -subj "/CN=GameChat Local CA"

openssl genrsa -out server.key 2048
openssl req -new -key server.key -out server.csr -subj "/CN=localhost"

cat > server.ext <<'EOF'
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alt_names
[alt_names]
DNS.1 = localhost
DNS.2 = gateway
DNS.3 = frontend
IP.1 = 127.0.0.1
EOF

openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt -days 825 -sha256 -extfile server.ext

rm -f server.csr server.ext ca.srl
chmod 644 ca.crt server.crt
chmod 640 server.key
echo "Generated local TLS CA and server certificate"
