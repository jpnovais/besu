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
package org.hyperledger.besu.consensus.rollup.blockcreation;

import org.hyperledger.besu.consensus.merge.blockcreation.MergeBlockCreator;
import org.hyperledger.besu.consensus.merge.blockcreation.MergeCoordinator;
import org.hyperledger.besu.consensus.merge.blockcreation.MergeMiningCoordinator;
import org.hyperledger.besu.consensus.merge.blockcreation.PayloadIdentifier;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.BlockValidator.Result;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.blockcreation.BlockTransactionSelector.TransactionSelectionResults;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.MiningParameters;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.sync.backwardsync.BackwardSyncContext;
import org.hyperledger.besu.ethereum.eth.transactions.sorter.AbstractPendingTransactionsSorter;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;

import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RollupMergeCoordinator extends MergeCoordinator implements MergeMiningCoordinator {

  private static final Logger LOG = LoggerFactory.getLogger(RollupMergeCoordinator.class);

  public RollupMergeCoordinator(
      final ProtocolContext protocolContext,
      final ProtocolSchedule protocolSchedule,
      final AbstractPendingTransactionsSorter pendingTransactions,
      final MiningParameters miningParams,
      final BackwardSyncContext backwardSyncContext) {
    super(
        protocolContext, protocolSchedule, pendingTransactions, miningParams, backwardSyncContext);
  }

  public static class BlockCreationResult {

    private final PayloadIdentifier blockIdentifier;
    private final Block block;
    private final Result blockExecutionResult;
    private final TransactionSelectionResults transactionSelectionResults;

    public BlockCreationResult(
        final PayloadIdentifier blockIdentifier,
        final Block block,
        final Result blockExecutionResult) {
      this.blockIdentifier = blockIdentifier;
      this.block = block;
      this.blockExecutionResult = blockExecutionResult;
      this.transactionSelectionResults = new TransactionSelectionResults();
    }

    public BlockCreationResult(
        final PayloadIdentifier blockIdentifier,
        final Block block,
        final Result blockExecutionResult,
        final TransactionSelectionResults transactionProcessingResults) {
      this.blockIdentifier = blockIdentifier;
      this.block = block;
      this.blockExecutionResult = blockExecutionResult;
      this.transactionSelectionResults = transactionProcessingResults;
    }

    public TransactionSelectionResults getTransactionSelectionResults() {
      return transactionSelectionResults;
    }

    public Block getBlock() {
      return block;
    }

    public PayloadIdentifier getBlockIdentifier() {
      return blockIdentifier;
    }

    public Result getBlockExecutionResult() {
      return blockExecutionResult;
    }
  }

  public BlockCreationResult createBlock(
      final BlockHeader parentHeader,
      final Long timestamp,
      final Address feeRecipient,
      final List<Transaction> transactions,
      final Bytes32 prevRandao,
      final Optional<Long> optionalBlockGasLimit) {
    final PayloadIdentifier payloadIdentifier =
        PayloadIdentifier.forPayloadParams(parentHeader.getBlockHash(), timestamp);
    final MergeBlockCreator mergeBlockCreator =
        this.mergeBlockCreator.forParams(
            parentHeader, Optional.ofNullable(feeRecipient), optionalBlockGasLimit);

    final Block block =
        mergeBlockCreator.createBlock(Optional.of(transactions), prevRandao, timestamp);

    Result result = validateBlock(block);
    if (result.blockProcessingOutputs.isPresent()) {
      mergeContext.putPayloadById(payloadIdentifier, block);
    } else {
      LOG.warn("Failed to execute new block {}, reason {}", block.getHash(), result.errorMessage);
    }

    return new BlockCreationResult(
        payloadIdentifier,
        block,
        result,
        (TransactionSelectionResults) block.getTransactionSelectionResults().get());
  }
}
