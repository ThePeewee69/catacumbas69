#!/bin/sh
set -e

# Copia todos os confs para o emptyDir (podemos escrever aqui, não no layer da imagem)
cp -r /pulsar/conf/. /conf/

# Injeta advertisedListeners: interno (pod IP) + externo (NodePort do NUC)
printf '\nadvertisedListeners=internal:pulsar://%s:6650,external:pulsar://192.168.86.35:31650\n' "${MY_POD_IP}" >> /conf/standalone.conf
printf '\nadvertisedAddress=localhost\n' >> /conf/standalone.conf
printf '\nmetadataStoreBackedBookkeeperPort=3181\n' >> /conf/standalone.conf

# Tenta fixar endereço estável para o bookie via bookkeeper.conf
printf '\nadvertisedAddress=localhost\nbookiePort=3181\n' >> /conf/bookkeeper.conf

# Limpa TUDO em /pulsar/data ao reiniciar.
# O BKCluster armazena cookies em vários locais (bookkeeper/, metadata/, etc.).
# Limpar subdirs específicos deixa sempre algum estado inconsistente.
# Com um único bookie não há recovery possível — estado fresco é a única solução.
PULSAR_DATA=/pulsar/data
if [ "$(ls -A ${PULSAR_DATA} 2>/dev/null)" ]; then
  echo "[init] Dados existentes detectados — a limpar ${PULSAR_DATA}/..."
  rm -rf "${PULSAR_DATA:?}"/*
  echo "[init] Feito — broker arranca com estado fresco."
else
  echo "[init] Instalação nova — sem dados para limpar."
fi
