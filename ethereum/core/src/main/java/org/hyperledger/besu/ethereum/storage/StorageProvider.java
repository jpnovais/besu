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
package org.hyperledger.besu.ethereum.storage;

import org.hyperledger.besu.ethereum.chain.BlockchainStorage;
import org.hyperledger.besu.ethereum.goquorum.GoQuorumPrivateStorage;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.worldstate.DataStorageFormat;
import org.hyperledger.besu.ethereum.worldstate.WorldStatePreimageStorage;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorage;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;
import org.hyperledger.besu.plugin.services.storage.SnappableKeyValueStorage;

import java.io.Closeable;

public interface StorageProvider extends Closeable {

  BlockchainStorage createBlockchainStorage(ProtocolSchedule protocolSchedule);

  WorldStateStorage createWorldStateStorage(DataStorageFormat dataStorageFormat);

  WorldStatePreimageStorage createWorldStatePreimageStorage();

  KeyValueStorage getStorageBySegmentIdentifier(SegmentIdentifier segment);

  SnappableKeyValueStorage getSnappableStorageBySegmentIdentifier(SegmentIdentifier segment);

  WorldStateStorage createPrivateWorldStateStorage();

  WorldStatePreimageStorage createPrivateWorldStatePreimageStorage();

  GoQuorumPrivateStorage createGoQuorumPrivateStorage();

  boolean isWorldStateIterable();
}
