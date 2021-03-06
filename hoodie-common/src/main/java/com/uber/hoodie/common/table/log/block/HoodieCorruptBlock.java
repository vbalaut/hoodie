/*
 * Copyright (c) 2016 Uber Technologies, Inc. (hoodie-dev-group@uber.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.hoodie.common.table.log.block;

import java.io.IOException;

/**
 * Corrupt block is emitted whenever the scanner finds the length of the block written at the
 * beginning does not match (did not find a EOF or a sync marker after the length)
 */
public class HoodieCorruptBlock implements HoodieLogBlock {

  private final byte[] corruptedBytes;

  private HoodieCorruptBlock(byte[] corruptedBytes) {
    this.corruptedBytes = corruptedBytes;
  }

  @Override
  public byte[] getBytes() throws IOException {
    return corruptedBytes;
  }

  @Override
  public HoodieLogBlockType getBlockType() {
    return HoodieLogBlockType.CORRUPT_BLOCK;
  }

  public static HoodieLogBlock fromBytes(byte[] content) {
    return new HoodieCorruptBlock(content);
  }
}
