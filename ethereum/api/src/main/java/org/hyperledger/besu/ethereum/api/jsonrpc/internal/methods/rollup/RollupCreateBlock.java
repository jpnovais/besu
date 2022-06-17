/*
 * Copyright Hyperledger Besu Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.rollup;

import org.hyperledger.besu.consensus.rollup.blockcreation.RollupMergeCoordinator;
import org.hyperledger.besu.consensus.rollup.blockcreation.RollupMergeCoordinator.BlockCreationResult;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.UnsignedLongParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.BlockResultFactory;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EngineGetPayloadResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.RollupCreateBlockResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionReceiptResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionReceiptRootResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionReceiptStatusResult;
import org.hyperledger.besu.ethereum.api.query.TransactionReceiptWithMetadata;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.core.encoding.TransactionDecoder;
import org.hyperledger.besu.ethereum.mainnet.TransactionReceiptType;
import org.hyperledger.besu.ethereum.rlp.RLPException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import io.vertx.core.Vertx;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RollupCreateBlock extends ExecutionEngineJsonRpcMethod {

  private static final Logger LOG = LoggerFactory.getLogger(RollupCreateBlock.class);
  private final RollupMergeCoordinator mergeCoordinator;
  private final BlockResultFactory blockResultFactory;

  public RollupCreateBlock(
      final Vertx vertx,
      final ProtocolContext protocolContext,
      final RollupMergeCoordinator mergeCoordinator,
      final BlockResultFactory blockResultFactory) {
    super(vertx, protocolContext);
    this.mergeCoordinator = mergeCoordinator;
    this.blockResultFactory = blockResultFactory;
  }

  @Override
  public String getName() {
    return RpcMethod.ROLLUP_CREATE_BLOCK.getMethodName();
  }

  @Override
  public JsonRpcResponse syncResponse(final JsonRpcRequestContext requestContext) {
    final Object requestId = requestContext.getRequest().getId();
    final Hash parentBlockHash;
    final List<Transaction> transactions;
    final Address suggestedRecipient;
    final UnsignedLongParameter blockGasLimit;
    final long timestamp;
    // final Boolean skipInvalidTransactions;
    final Bytes32 prevRandao;

    try {
      parentBlockHash = requestContext.getRequiredParameter(0, Hash.class);
      final List<?> rawTransactions = requestContext.getRequiredParameter(1, List.class);
      prevRandao = requestContext.getRequiredParameter(2, Hash.class);
      suggestedRecipient = requestContext.getRequiredParameter(3, Address.class);
      blockGasLimit = requestContext.getRequiredParameter(4, UnsignedLongParameter.class);
      timestamp = Long.decode(requestContext.getRequiredParameter(5, String.class));
      // skipInvalidTransactions = requestContext.getRequiredParameter(4, Boolean.class);
      System.out.println("rawTransactions: " + rawTransactions);
      transactions =
          rawTransactions.stream()
              .map(Object::toString)
              .map(Bytes::fromHexString)
              .map(TransactionDecoder::decodeOpaqueBytes)
              .collect(Collectors.toList());
    } catch (final RLPException | IllegalArgumentException e) {
      return new JsonRpcErrorResponse(
          requestContext.getRequest().getId(), JsonRpcError.INVALID_PARAMS);
    }
    final Optional<BlockHeader> parentBlock =
        protocolContext.getBlockchain().getBlockHeader(parentBlockHash);
    if (parentBlock.isEmpty()) {
      return replyWithStatus(requestId, RollupCreateBlockStatus.INVALID_TERMINAL_BLOCK);
    }

    try {

      BlockCreationResult result =
          mergeCoordinator.createBlock(
              parentBlock.get(),
              timestamp,
              suggestedRecipient,
              transactions,
              prevRandao,
              Optional.of(blockGasLimit.getValue()));

      EngineGetPayloadResult payloadResult =
          blockResultFactory.enginePayloadTransactionComplete(result.getBlock());

      return new JsonRpcSuccessResponse(
          requestId,
          new RollupCreateBlockResult(
              RollupCreateBlockStatus.PROCESSED,
              result.getBlockIdentifier(),
              payloadResult,
              failedTransactionsResults(result)));
    } catch (Exception e) {
      LOG.error("Failed to creat block.", e);
      throw e;
    }
  }

  private List<TransactionReceiptResult> failedTransactionsResults(
      final BlockCreationResult result) {
    final Block block = result.getBlock();
    final List<Transaction> transactions = block.getBody().getTransactions();
    final List<TransactionReceipt> transactionReceipts =
        result.getBlockExecutionResult().blockProcessingOutputs.get().receipts;
    final List<TransactionReceiptResult> failedTransactions = new ArrayList<>();

    for (int txIndex = 0; txIndex < transactions.size(); txIndex++) {
      var txReceipt = transactionReceipts.get(txIndex);

      if (isFailedTransaction(txReceipt)) {
        var tx = transactions.get(txIndex);
        var txReceiptMeta =
            TransactionReceiptWithMetadata.create(
                txReceipt,
                tx,
                tx.getHash(),
                // TODO: double check if this transaction index is block based (it seems to me to
                // be)
                //  or is whole blockchain index;
                txIndex,
                txReceipt.getCumulativeGasUsed(),
                block.getHeader().getBaseFee(),
                block.getHash(),
                block.getHeader().getNumber());

        failedTransactions.add(toTransactionReceiptResult(txReceiptMeta));
      }
    }

    return failedTransactions;
  }

  private static boolean isFailedTransaction(final TransactionReceipt txReceipt) {
    return txReceipt.getStatus() == 0;
  }

  static TransactionReceiptResult toTransactionReceiptResult(
      final TransactionReceiptWithMetadata receipt) {
    if (receipt.getReceipt().getTransactionReceiptType() == TransactionReceiptType.ROOT) {
      return new TransactionReceiptRootResult(receipt);
    } else {
      return new TransactionReceiptStatusResult(receipt);
    }
  }

  private JsonRpcResponse replyWithStatus(
      final Object requestId, final RollupCreateBlockStatus status) {
    return new JsonRpcSuccessResponse(
        requestId, new RollupCreateBlockResult(status, null, null, null));
  }
}
