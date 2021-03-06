/*
 * Copyright 2012-2019 Aerospike, Inc.
 *
 * Portions may be licensed to Aerospike, Inc. under one or more contributor
 * license agreements WHICH ARE COMPATIBLE WITH THE APACHE LICENSE, VERSION 2.0.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.aerospike.client.async;

import java.util.Arrays;
import java.util.List;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.BatchRead;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.cluster.Cluster;
import com.aerospike.client.command.BatchNode;
import com.aerospike.client.command.Buffer;
import com.aerospike.client.command.Command;
import com.aerospike.client.listener.BatchListListener;
import com.aerospike.client.listener.BatchSequenceListener;
import com.aerospike.client.listener.ExistsArrayListener;
import com.aerospike.client.listener.ExistsSequenceListener;
import com.aerospike.client.listener.RecordArrayListener;
import com.aerospike.client.listener.RecordSequenceListener;
import com.aerospike.client.policy.BatchPolicy;
import com.aerospike.client.policy.Replica;

public final class AsyncBatch {
	//-------------------------------------------------------
	// ReadList
	//-------------------------------------------------------

	public static final class ReadListExecutor extends AsyncMultiExecutor {
		private final BatchListListener listener;
		private final List<BatchRead> records;

		public ReadListExecutor(
			EventLoop eventLoop,
			Cluster cluster,
			BatchPolicy policy,
			BatchListListener listener,
			List<BatchRead> records
		) {
			super(eventLoop, cluster);
			this.listener = listener;
			this.records = records;

			// Create commands.
			List<BatchNode> batchNodes = BatchNode.generateList(cluster, policy, records);
			AsyncMultiCommand[] tasks = new AsyncMultiCommand[batchNodes.size()];
			int count = 0;

			for (BatchNode batchNode : batchNodes) {
				tasks[count++] = new ReadListCommand(this, batchNode, policy, records);
			}
			// Dispatch commands to nodes.
			execute(tasks, 0);
		}

		protected void onSuccess() {
			listener.onSuccess(records);
		}

		protected void onFailure(AerospikeException ae) {
			listener.onFailure(ae);
		}
	}

	private static final class ReadListCommand extends AsyncMultiCommand {
		private final BatchNode batch;
		private final BatchPolicy policy;
		private final List<BatchRead> records;

		public ReadListCommand(
			AsyncMultiExecutor parent,
			BatchNode batch,
			BatchPolicy policy,
			List<BatchRead> records
		) {
			super(parent, batch.node, policy, false);
			this.batch = batch;
			this.policy = policy;
			this.records = records;
		}

		@Override
		protected void writeBuffer() {
			setBatchRead(policy, records, batch);
		}

		@Override
		protected void parseRow(Key key) {
			BatchRead record = records.get(batchIndex);

			if (Arrays.equals(key.digest, record.key.digest)) {
				if (resultCode == 0) {
					record.record = parseRecord();
				}
			}
			else {
				throw new AerospikeException.Parse("Unexpected batch key returned: " + key.namespace + ',' + Buffer.bytesToHexString(key.digest) + ',' + batchIndex);
			}
		}

		@Override
		protected boolean retryBatch(Runnable other, long deadline) {
			if (parent.done || ! (policy.replica == Replica.SEQUENCE || policy.replica == Replica.PREFER_RACK)) {
				return false;
			}

			// Retry requires keys for this node to be split among other nodes.
			// This can cause an exponential number of commands.
			List<BatchNode> batchNodes = BatchNode.generateList(parent.cluster, policy, records, sequence, batch);

			if (batchNodes.size() == 1 && batchNodes.get(0).node == batch.node) {
				// Batch node is the same.  Go through normal retry.
				return false;
			}

			AsyncMultiCommand[] cmds = new AsyncMultiCommand[batchNodes.size()];
			int count = 0;

			for (BatchNode batchNode : batchNodes) {
				AsyncMultiCommand cmd = new ReadListCommand(parent, batchNode, policy, records);
				cmd.sequence = sequence;
				cmds[count++] = cmd;
			}
			parent.executeBatchRetry(cmds, this, other, deadline);
			return true;
		}
	}

	//-------------------------------------------------------
	// ReadSequence
	//-------------------------------------------------------

	public static final class ReadSequenceExecutor extends AsyncMultiExecutor {
		private final BatchSequenceListener listener;

		public ReadSequenceExecutor(
			EventLoop eventLoop,
			Cluster cluster,
			BatchPolicy policy,
			BatchSequenceListener listener,
			List<BatchRead> records
		) {
			super(eventLoop, cluster);
			this.listener = listener;

			// Create commands.
			List<BatchNode> batchNodes = BatchNode.generateList(cluster, policy, records);
			AsyncMultiCommand[] tasks = new AsyncMultiCommand[batchNodes.size()];
			int count = 0;

			for (BatchNode batchNode : batchNodes) {
				tasks[count++] = new ReadSequenceCommand(this, batchNode, policy, listener, records);
			}
			// Dispatch commands to nodes.
			execute(tasks, 0);
		}

		protected void onSuccess() {
			listener.onSuccess();
		}

		protected void onFailure(AerospikeException ae) {
			listener.onFailure(ae);
		}
	}

	private static final class ReadSequenceCommand extends AsyncMultiCommand {
		private final BatchNode batch;
		private final BatchPolicy policy;
		private final BatchSequenceListener listener;
		private final List<BatchRead> records;

		public ReadSequenceCommand(
			AsyncMultiExecutor parent,
			BatchNode batch,
			BatchPolicy policy,
			BatchSequenceListener listener,
			List<BatchRead> records
		) {
			super(parent, batch.node, policy, false);
			this.batch = batch;
			this.policy = policy;
			this.listener = listener;
			this.records = records;
		}

		@Override
		protected void writeBuffer() {
			setBatchRead(policy, records, batch);
		}

		@Override
		protected void parseRow(Key key) {
			BatchRead record = records.get(batchIndex);

			if (Arrays.equals(key.digest, record.key.digest)) {
				if (resultCode == 0) {
					record.record = parseRecord();
				}
				listener.onRecord(record);
			}
			else {
				throw new AerospikeException.Parse("Unexpected batch key returned: " + key.namespace + ',' + Buffer.bytesToHexString(key.digest) + ',' + batchIndex);
			}
		}

		@Override
		protected boolean retryBatch(Runnable other, long deadline) {
			if (parent.done || ! (policy.replica == Replica.SEQUENCE || policy.replica == Replica.PREFER_RACK)) {
				return false;
			}

			// Retry requires keys for this node to be split among other nodes.
			// This can cause an exponential number of commands.
			List<BatchNode> batchNodes = BatchNode.generateList(parent.cluster, policy, records, sequence, batch);

			if (batchNodes.size() == 1 && batchNodes.get(0).node == batch.node) {
				// Batch node is the same.  Go through normal retry.
				return false;
			}

			AsyncMultiCommand[] cmds = new AsyncMultiCommand[batchNodes.size()];
			int count = 0;

			for (BatchNode batchNode : batchNodes) {
				AsyncMultiCommand cmd = new ReadSequenceCommand(parent, batchNode, policy, listener, records);
				cmd.sequence = sequence;
				cmds[count++] = cmd;
			}
			parent.executeBatchRetry(cmds, this, other, deadline);
			return true;
		}
	}

	//-------------------------------------------------------
	// GetArray
	//-------------------------------------------------------

	public static final class GetArrayExecutor extends BaseExecutor {
		private final RecordArrayListener listener;
		private final Record[] recordArray;

		public GetArrayExecutor(
			EventLoop eventLoop,
			Cluster cluster,
			BatchPolicy policy,
			RecordArrayListener listener,
			Key[] keys,
			String[] binNames,
			int readAttr
		) {
			super(eventLoop, cluster, policy, keys);
			this.recordArray = new Record[keys.length];
			this.listener = listener;

			// Create commands.
			AsyncMultiCommand[] tasks = new AsyncMultiCommand[super.taskSize];
			int count = 0;

			for (BatchNode batchNode : batchNodes) {
				tasks[count++] = new GetArrayCommand(this, batchNode, policy, keys, binNames, recordArray, readAttr);
			}
			// Dispatch commands to nodes.
			execute(tasks, 0);
		}

		protected void onSuccess() {
			listener.onSuccess(keys, recordArray);
		}

		protected void onFailure(AerospikeException ae) {
			listener.onFailure(ae);
		}
	}

	private static final class GetArrayCommand extends AsyncMultiCommand {
		private final BatchNode batch;
		private final BatchPolicy policy;
		private final Key[] keys;
		private final String[] binNames;
		private final Record[] records;
		private final int readAttr;

		public GetArrayCommand(
			AsyncMultiExecutor parent,
			BatchNode batch,
			BatchPolicy policy,
			Key[] keys,
			String[] binNames,
			Record[] records,
			int readAttr
		) {
			super(parent, batch.node, policy, false);
			this.batch = batch;
			this.policy = policy;
			this.keys = keys;
			this.binNames = binNames;
			this.records = records;
			this.readAttr = readAttr;
		}

		@Override
		protected void writeBuffer() {
			setBatchRead(policy, keys, batch, binNames, readAttr);
		}

		@Override
		protected void parseRow(Key key) {
			if (Arrays.equals(key.digest, keys[batchIndex].digest)) {
				if (resultCode == 0) {
					records[batchIndex] = parseRecord();
				}
			}
			else {
				throw new AerospikeException.Parse("Unexpected batch key returned: " + key.namespace + ',' + Buffer.bytesToHexString(key.digest) + ',' + batchIndex);
			}
		}

		@Override
		protected boolean retryBatch(Runnable other, long deadline) {
			if (parent.done || ! (policy.replica == Replica.SEQUENCE || policy.replica == Replica.PREFER_RACK)) {
				return false;
			}

			// Retry requires keys for this node to be split among other nodes.
			// This can cause an exponential number of commands.
			List<BatchNode> batchNodes = BatchNode.generateList(parent.cluster, policy, keys, sequence, batch);

			if (batchNodes.size() == 1 && batchNodes.get(0).node == batch.node) {
				// Batch node is the same.  Go through normal retry.
				return false;
			}

			AsyncMultiCommand[] cmds = new AsyncMultiCommand[batchNodes.size()];
			int count = 0;

			for (BatchNode batchNode : batchNodes) {
				AsyncMultiCommand cmd = new GetArrayCommand(parent, batchNode, policy, keys, binNames, records, readAttr);
				cmd.sequence = sequence;
				cmds[count++] = cmd;
			}
			parent.executeBatchRetry(cmds, this, other, deadline);
			return true;
		}
	}

	//-------------------------------------------------------
	// GetSequence
	//-------------------------------------------------------

	public static final class GetSequenceExecutor extends BaseExecutor {
		private final RecordSequenceListener listener;

		public GetSequenceExecutor(
			EventLoop eventLoop,
			Cluster cluster,
			BatchPolicy policy,
			RecordSequenceListener listener,
			Key[] keys,
			String[] binNames,
			int readAttr
		) {
			super(eventLoop, cluster, policy, keys);
			this.listener = listener;

			// Create commands.
			AsyncMultiCommand[] tasks = new AsyncMultiCommand[super.taskSize];
			int count = 0;

			for (BatchNode batchNode : batchNodes) {
				tasks[count++] = new GetSequenceCommand(this, batchNode, policy, keys, binNames, listener, readAttr);
			}
			// Dispatch commands to nodes.
			execute(tasks, 0);
		}

		@Override
		protected void onSuccess() {
			listener.onSuccess();
		}

		@Override
		protected void onFailure(AerospikeException ae) {
			listener.onFailure(ae);
		}
	}

	private static final class GetSequenceCommand extends AsyncMultiCommand {
		private final BatchNode batch;
		private final BatchPolicy policy;
		private final Key[] keys;
		private final String[] binNames;
		private final RecordSequenceListener listener;
		private final int readAttr;

		public GetSequenceCommand(
			AsyncMultiExecutor parent,
			BatchNode batch,
			BatchPolicy policy,
			Key[] keys,
			String[] binNames,
			RecordSequenceListener listener,
			int readAttr
		) {
			super(parent, batch.node, policy, false);
			this.batch = batch;
			this.policy = policy;
			this.keys = keys;
			this.binNames = binNames;
			this.listener = listener;
			this.readAttr = readAttr;
		}

		@Override
		protected void writeBuffer() {
			setBatchRead(policy, keys, batch, binNames, readAttr);
		}

		@Override
		protected void parseRow(Key key) {
			Key keyOrig = keys[batchIndex];

			if (Arrays.equals(key.digest, keyOrig.digest)) {
				if (resultCode == 0) {
					Record record = parseRecord();
					listener.onRecord(keyOrig, record);
				}
				else {
					listener.onRecord(keyOrig, null);
				}
			}
			else {
				throw new AerospikeException.Parse("Unexpected batch key returned: " + key.namespace + ',' + Buffer.bytesToHexString(key.digest) + ',' + batchIndex);
			}
		}

		@Override
		protected boolean retryBatch(Runnable other, long deadline) {
			if (parent.done || ! (policy.replica == Replica.SEQUENCE || policy.replica == Replica.PREFER_RACK)) {
				return false;
			}

			// Retry requires keys for this node to be split among other nodes.
			// This can cause an exponential number of commands.
			List<BatchNode> batchNodes = BatchNode.generateList(parent.cluster, policy, keys, sequence, batch);

			if (batchNodes.size() == 1 && batchNodes.get(0).node == batch.node) {
				// Batch node is the same.  Go through normal retry.
				return false;
			}

			AsyncMultiCommand[] cmds = new AsyncMultiCommand[batchNodes.size()];
			int count = 0;

			for (BatchNode batchNode : batchNodes) {
				AsyncMultiCommand cmd = new GetSequenceCommand(parent, batchNode, policy, keys, binNames, listener, readAttr);
				cmd.sequence = sequence;
				cmds[count++] = cmd;
			}
			parent.executeBatchRetry(cmds, this, other, deadline);
			return true;
		}
	}

	//-------------------------------------------------------
	// ExistsArray
	//-------------------------------------------------------

	public static final class ExistsArrayExecutor extends BaseExecutor {
		private final ExistsArrayListener listener;
		private final boolean[] existsArray;

		public ExistsArrayExecutor(
			EventLoop eventLoop,
			Cluster cluster,
			BatchPolicy policy,
			Key[] keys,
			ExistsArrayListener listener
		) {
			super(eventLoop, cluster, policy, keys);
			this.existsArray = new boolean[keys.length];
			this.listener = listener;

			// Create commands.
			AsyncMultiCommand[] tasks = new AsyncMultiCommand[super.taskSize];
			int count = 0;

			for (BatchNode batchNode : batchNodes) {
				tasks[count++] = new ExistsArrayCommand(this, batchNode, policy, keys, existsArray);
			}
			// Dispatch commands to nodes.
			execute(tasks, 0);
		}

		protected void onSuccess() {
			listener.onSuccess(keys, existsArray);
		}

		protected void onFailure(AerospikeException ae) {
			listener.onFailure(ae);
		}
	}

	private static final class ExistsArrayCommand extends AsyncMultiCommand {
		private final BatchNode batch;
		private final BatchPolicy policy;
		private final Key[] keys;
		private final boolean[] existsArray;

		public ExistsArrayCommand(
			AsyncMultiExecutor parent,
			BatchNode batch,
			BatchPolicy policy,
			Key[] keys,
			boolean[] existsArray
		) {
			super(parent, batch.node, policy, false);
			this.batch = batch;
			this.policy = policy;
			this.keys = keys;
			this.existsArray = existsArray;
		}

		@Override
		protected void writeBuffer() {
			setBatchRead(policy, keys, batch, null, Command.INFO1_READ | Command.INFO1_NOBINDATA);
		}

		@Override
		protected void parseRow(Key key) {
			if (opCount > 0) {
				throw new AerospikeException.Parse("Received bins that were not requested!");
			}

			if (Arrays.equals(key.digest, keys[batchIndex].digest)) {
				existsArray[batchIndex] = resultCode == 0;
			}
			else {
				throw new AerospikeException.Parse("Unexpected batch key returned: " + key.namespace + ',' + Buffer.bytesToHexString(key.digest) + ',' + batchIndex);
			}
		}

		@Override
		protected boolean retryBatch(Runnable other, long deadline) {
			if (parent.done || ! (policy.replica == Replica.SEQUENCE || policy.replica == Replica.PREFER_RACK)) {
				return false;
			}

			// Retry requires keys for this node to be split among other nodes.
			// This can cause an exponential number of commands.
			List<BatchNode> batchNodes = BatchNode.generateList(parent.cluster, policy, keys, sequence, batch);

			if (batchNodes.size() == 1 && batchNodes.get(0).node == batch.node) {
				// Batch node is the same.  Go through normal retry.
				return false;
			}

			AsyncMultiCommand[] cmds = new AsyncMultiCommand[batchNodes.size()];
			int count = 0;

			for (BatchNode batchNode : batchNodes) {
				AsyncMultiCommand cmd = new ExistsArrayCommand(parent, batchNode, policy, keys, existsArray);
				cmd.sequence = sequence;
				cmds[count++] = cmd;
			}
			parent.executeBatchRetry(cmds, this, other, deadline);
			return true;
		}
	}

	//-------------------------------------------------------
	// ExistsSequence
	//-------------------------------------------------------

	public static final class ExistsSequenceExecutor extends BaseExecutor {
		private final ExistsSequenceListener listener;

		public ExistsSequenceExecutor(
			EventLoop eventLoop,
			Cluster cluster,
			BatchPolicy policy,
			Key[] keys,
			ExistsSequenceListener listener
		) {
			super(eventLoop, cluster, policy, keys);
			this.listener = listener;

			// Create commands.
			AsyncMultiCommand[] tasks = new AsyncMultiCommand[super.taskSize];
			int count = 0;

			for (BatchNode batchNode : batchNodes) {
				tasks[count++] = new ExistsSequenceCommand(this, batchNode, policy, keys, listener);
			}
			// Dispatch commands to nodes.
			execute(tasks, 0);
		}

		protected void onSuccess() {
			listener.onSuccess();
		}

		protected void onFailure(AerospikeException ae) {
			listener.onFailure(ae);
		}
	}

	private static final class ExistsSequenceCommand extends AsyncMultiCommand {
		private final BatchNode batch;
		private final BatchPolicy policy;
		private final Key[] keys;
		private final ExistsSequenceListener listener;

		public ExistsSequenceCommand(
			AsyncMultiExecutor parent,
			BatchNode batch,
			BatchPolicy policy,
			Key[] keys,
			ExistsSequenceListener listener
		) {
			super(parent, batch.node, policy, false);
			this.batch = batch;
			this.policy = policy;
			this.keys = keys;
			this.listener = listener;
		}

		@Override
		protected void writeBuffer() {
			setBatchRead(policy, keys, batch, null, Command.INFO1_READ | Command.INFO1_NOBINDATA);
		}

		@Override
		protected void parseRow(Key key) {
			if (opCount > 0) {
				throw new AerospikeException.Parse("Received bins that were not requested!");
			}

			Key keyOrig = keys[batchIndex];

			if (Arrays.equals(key.digest, keyOrig.digest)) {
				listener.onExists(keyOrig, resultCode == 0);
			}
			else {
				throw new AerospikeException.Parse("Unexpected batch key returned: " + key.namespace + ',' + Buffer.bytesToHexString(key.digest) + ',' + batchIndex);
			}
		}

		@Override
		protected boolean retryBatch(Runnable other, long deadline) {
			if (parent.done || ! (policy.replica == Replica.SEQUENCE || policy.replica == Replica.PREFER_RACK)) {
				return false;
			}

			// Retry requires keys for this node to be split among other nodes.
			// This can cause an exponential number of commands.
			List<BatchNode> batchNodes = BatchNode.generateList(parent.cluster, policy, keys, sequence, batch);

			if (batchNodes.size() == 1 && batchNodes.get(0).node == batch.node) {
				// Batch node is the same.  Go through normal retry.
				return false;
			}

			AsyncMultiCommand[] cmds = new AsyncMultiCommand[batchNodes.size()];
			int count = 0;

			for (BatchNode batchNode : batchNodes) {
				AsyncMultiCommand cmd = new ExistsSequenceCommand(parent, batchNode, policy, keys, listener);
				cmd.sequence = sequence;
				cmds[count++] = cmd;
			}
			parent.executeBatchRetry(cmds, this, other, deadline);
			return true;
		}
	}

	//-------------------------------------------------------
	// BaseExecutor
	//-------------------------------------------------------

	private static abstract class BaseExecutor extends AsyncMultiExecutor {
		protected final Key[] keys;
		protected final List<BatchNode> batchNodes;
		protected final int taskSize;

		public BaseExecutor(EventLoop eventLoop, Cluster cluster, BatchPolicy policy, Key[] keys) {
			super(eventLoop, cluster);
			this.keys = keys;
			this.batchNodes = BatchNode.generateList(cluster, policy, keys);
			this.taskSize = batchNodes.size();
		}
	}
}
