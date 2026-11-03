#!/bin/sh
set -e

# Copia todos os confs para o emptyDir (podemos escrever aqui, não no layer da imagem)
cp -r /pulsar/conf/. /conf/

# Injeta advertisedListeners: interno (pod IP) + externo (NodePort do NUC)
printf '\nadvertisedListeners=internal:pulsar://%s:6650,external:pulsar://192.168.86.35:31650\n' "${MY_POD_IP}" >> /conf/standalone.conf

# advertisedAddress=localhost dá ao bookie um ID estável (localhost:<porta>) que não muda com o IP do pod.
# No modo standalone com RocksDB o bookie lê a configuração do standalone.conf (não do bookkeeper.conf) e,
# quando já existe um cookie em /pulsar/data, volta a usar o endereço e a porta que lá estão gravados.
# A linha tem de ser SUBSTITUÍDA, não acrescentada: o standalone.conf já traz "advertisedAddress=" e, com a chave
# repetida, o bookie (Commons Configuration) fica com o primeiro valor (vazio → IP do pod), enquanto o broker
# (java.util.Properties) fica com o último. Foi isso que gravou o IP do pod no cookie.
sed -i 's/^advertisedAddress=.*/advertisedAddress=localhost/' /conf/standalone.conf
if [ "$(grep -c '^advertisedAddress=' /conf/standalone.conf)" != "1" ]; then
  echo "[init] ERRO: advertisedAddress não ficou definido exatamente uma vez em standalone.conf" >&2
  exit 1
fi

# Limpeza só a pedido: PULSAR_WIPE_DATA=true no initContainer do Deployment apaga /pulsar/data neste arranque.
# Usar só se o bookie não arrancar ou não encontrar os ledgers — perde-se tudo. Voltar a pôr false depois.
PULSAR_DATA=/pulsar/data
if [ "${PULSAR_WIPE_DATA:-false}" = "true" ]; then
  echo "[init] PULSAR_WIPE_DATA=true — a apagar ${PULSAR_DATA}/..."
  rm -rf "${PULSAR_DATA:?}"/*
  echo "[init] Feito — broker arranca com estado fresco."
elif [ "$(ls -A ${PULSAR_DATA} 2>/dev/null)" ]; then
  echo "[init] Dados existentes em ${PULSAR_DATA} — mantidos."
else
  echo "[init] Instalação nova — sem dados."
fi
