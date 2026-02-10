#!/bin/bash
# Chaos Monkey Script to test Kubernetes Pod Disruption Budgets (PDB) & Resiliency

NAMESPACE="portfolio-ecosystem"
TARGET_DEPLYOMENT="core-ledger"
SLEEP_TIME=30

echo "Starting Chaos Monkey in namespace: $NAMESPACE"

while true; do
  echo "Fetching pods for deployment: $TARGET_DEPLYOMENT..."
  POD=$(kubectl get pods -n $NAMESPACE -l app=$TARGET_DEPLYOMENT -o jsonpath='{.items[0].metadata.name}')
  
  if [ -z "$POD" ]; then
    echo "No pods found! System might already be down or completely healed."
  else
    echo "Killing pod: $POD"
    kubectl delete pod $POD -n $NAMESPACE
    echo "Pod $POD terminated. Simulating node failure."
  fi

  echo "Sleeping for $SLEEP_TIME seconds before next chaos event..."
  sleep $SLEEP_TIME
done
