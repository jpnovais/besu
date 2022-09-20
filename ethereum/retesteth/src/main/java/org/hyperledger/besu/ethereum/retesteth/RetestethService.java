/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.retesteth;

import org.hyperledger.besu.ethereum.api.jsonrpc.JsonRpcConfiguration;
import org.hyperledger.besu.ethereum.api.jsonrpc.JsonRpcHttpService;
import org.hyperledger.besu.ethereum.api.jsonrpc.authentication.DefaultAuthenticationService;
import org.hyperledger.besu.ethereum.api.jsonrpc.health.HealthService;
import org.hyperledger.besu.ethereum.api.jsonrpc.health.LivenessCheck;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.DebugAccountRange;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.DebugStorageRangeAt;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.EthBlockNumber;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.EthCoinbase;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.EthGetBalance;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.EthGetBlockByHash;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.EthGetBlockByNumber;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.EthGetCode;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.EthGetTransactionCount;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.EthSendRawTransaction;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.JsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.Web3ClientVersion;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.EngineForkchoiceUpdated;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.EngineGetPayload;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.EngineNewPayload;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.rollup.RollupCreatePayload;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.BlockResultFactory;
import org.hyperledger.besu.ethereum.core.Synchronizer;
import org.hyperledger.besu.ethereum.retesteth.methods.TestGetLogHash;
import org.hyperledger.besu.ethereum.retesteth.methods.TestImportRawBlock;
import org.hyperledger.besu.ethereum.retesteth.methods.TestMineBlocks;
import org.hyperledger.besu.ethereum.retesteth.methods.TestModifyTimestamp;
import org.hyperledger.besu.ethereum.retesteth.methods.TestRewindToBlock;
import org.hyperledger.besu.ethereum.retesteth.methods.TestSetChainParams;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.nat.NatService;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings({"UnusedMethod", "UnusedVariable", "FieldCanBeFinal"})
public class RetestethService {

  private static final Logger LOG = LoggerFactory.getLogger(RetestethService.class);
  private final Synchronizer sync = new DummySynchronizer();

  private final RetestethContext retestethContext;
  private final String clientVersion;

  private final Vertx vertx;
  private final Vertx syncVertx;
  private JsonRpcHttpService jsonRpcHttpService;
  private Map<String, JsonRpcMethod> rpcMethods;

  public RetestethService(
      final String clientVersion,
      final RetestethConfiguration retestethConfiguration,
      final JsonRpcConfiguration jsonRpcConfiguration) {
    this.clientVersion = clientVersion;
    vertx = Vertx.vertx();
    syncVertx = Vertx.vertx(new VertxOptions().setWorkerPoolSize(1));
    retestethContext = new RetestethContext(__ -> {}, __ -> this.resetRpcMethods());

    this.rpcMethods = rpcMethods(false);

    final NatService natService = new NatService(Optional.empty());
    jsonRpcHttpService =
        new JsonRpcHttpService(
            vertx,
            retestethConfiguration.getDataPath(),
            jsonRpcConfiguration,
            new NoOpMetricsSystem(),
            natService,
            this::getRpcMethods,
            DefaultAuthenticationService.create(vertx, jsonRpcConfiguration),
            new HealthService(new LivenessCheck()),
            HealthService.ALWAYS_HEALTHY);
  }

  public Map<String, JsonRpcMethod> getRpcMethods() {
    return rpcMethods;
  }

  private void resetRpcMethods() {
    this.rpcMethods = rpcMethods(true);
  }

  private Map<String, JsonRpcMethod> rpcMethods(final boolean engineApiEnabled) {
    final BlockResultFactory blockResult = new BlockResultFactory();

    final Map<String, JsonRpcMethod> jsonRpcMethods =
        mapOf(
            new Web3ClientVersion(clientVersion),
            new TestSetChainParams(retestethContext),
            new TestImportRawBlock(retestethContext),
            new TestModifyTimestamp(retestethContext),
            new TestMineBlocks(retestethContext),
            new TestGetLogHash(retestethContext),
            new TestRewindToBlock(retestethContext),
            new EthBlockNumber(retestethContext::getBlockchainQueries, true),
            new EthGetBlockByNumber(
                retestethContext::getBlockchainQueries, blockResult, sync, true),
            new DebugAccountRange(retestethContext::getBlockchainQueries),
            new EthGetBalance(retestethContext::getBlockchainQueries),
            new EthGetBlockByHash(retestethContext::getBlockchainQueries, blockResult, true),
            new EthGetCode(retestethContext::getBlockchainQueries, Optional.empty()),
            new EthGetTransactionCount(
                retestethContext::getBlockchainQueries, retestethContext::getPendingTransactions),
            new EthCoinbase(retestethContext.getRollupCoordinator()),
            new DebugStorageRangeAt(
                retestethContext::getBlockchainQueries, retestethContext::getBlockReplay, true),
            new EthSendRawTransaction(retestethContext::getTransactionPool, true));
    if (engineApiEnabled) {
      jsonRpcMethods.putAll(
          mapOf(
              new EngineNewPayload(
                  syncVertx,
                  retestethContext.getProtocolContext(),
                  retestethContext.getRollupCoordinator(),
                  retestethContext.getEthPeers()),
              new EngineGetPayload(syncVertx, retestethContext.getProtocolContext(), blockResult),
              new EngineForkchoiceUpdated(
                  syncVertx,
                  retestethContext.getProtocolContext(),
                  retestethContext.getRollupCoordinator()),
              new RollupCreatePayload(
                  syncVertx,
                  retestethContext.getProtocolContext(),
                  retestethContext.getRollupCoordinator(),
                  new BlockResultFactory()
                  )));
    }

    return jsonRpcMethods;
  }

  public void start() {
    jsonRpcHttpService.start();
  }

  public void close() {
    stop();
  }

  public void stop() {
    jsonRpcHttpService.stop();
    vertx.close();
  }

  private static Map<String, JsonRpcMethod> mapOf(final JsonRpcMethod... rpcMethods) {
    return Arrays.stream(rpcMethods)
        .collect(Collectors.toMap(JsonRpcMethod::getName, rpcMethod -> rpcMethod));
  }
}
