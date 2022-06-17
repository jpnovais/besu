package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.rollup;

public enum RollupCreateBlockStatus {
  INVALID_TERMINAL_BLOCK,
  INVALID_TRANSACTIONS,
  BLOCK_GAS_LIMIT_EXCEEDED,
  PROCESSED;
}
