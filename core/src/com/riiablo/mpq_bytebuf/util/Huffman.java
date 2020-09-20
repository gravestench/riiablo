package com.riiablo.mpq_bytebuf.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.Map;
import java.util.TreeMap;

import com.riiablo.logger.LogManager;
import com.riiablo.logger.Logger;

public final class Huffman {
  private static final Logger log = LogManager.getLogger(Huffman.class);

  private static final int[] BIT_MASKS = new int[Integer.SIZE + 1];
  static {
    for (int i = 1; i < Integer.SIZE; i++) {
      BIT_MASKS[i] = (BIT_MASKS[i - 1] << 1) + 1;
    }
  }

  private static final class Node {
    Node parent;
    final Node[] child = new Node[2];
    Node next;
    Node prev;
    int value;
    int probability;

    void treeSwap(Node with) {
      Node temp;

      if (parent == with.parent) {
        temp = parent.child[0];
        parent.child[0] = parent.child[1];
        parent.child[1] = temp;
      } else {
        if (with.parent.child[0] == with) with.parent.child[0] = this;
        else with.parent.child[1] = this;
        if (this.parent.child[0] == this) this.parent.child[0] = with;
        else this.parent.child[1] = with;
      }

      temp = parent;
      parent = with.parent;
      with.parent = temp;
    }

    void insertAfter(Node where) {
      prev = where;
      next = where.next;
      where.next = this;
      next.prev = this;
    }

    void listSwap(Node with) {
      if (next == with) {
        next = with.next;
        with.next = this;
        with.prev = prev;
        prev = with;

        with.prev.next = with;
        next.prev = this;
      } else if (prev == with) {
        prev = with.prev;
        with.prev = this;
        with.next = next;
        next = with;

        with.next.prev = with;
        prev.next = this;
      } else {
        Node temp = prev;
        prev = with.prev;
        with.prev = temp;

        temp = next;
        next = with.next;
        with.next = temp;

        prev.next = this;
        next.prev = this;

        with.prev.next = with;
        with.next.prev = with;
      }
    }

    void newList() {
      prev = next = this;
    }

    Node removeFromList() {
      if (this == next) return null;

      prev.next = next;
      next.prev = prev;

      return next;
    }

    void joinList(Node list) {
      Node tail = prev;

      prev = list.prev;
      prev.next = this;

      list.prev = tail;
      tail.next = list;
    }
  }

  private Node nodes = null;
  private TreeMap<Integer, Node> sorted2 = new TreeMap<>();

  private Node root = null;

  private int bitBuffer;
  private byte bitNumber;
  private ByteBuf source;

  private void setSource(ByteBuf source) {
    this.source = source;
    bitBuffer = 0;
    bitNumber = 0;
  }

  private int getBits(int bits) {
    while (bitNumber < bits) {
      bitBuffer |= source.readUnsignedByte() << bitNumber;
      bitNumber += Byte.SIZE;
    }

    int result = bitBuffer & BIT_MASKS[bits];
    bitBuffer >>>= bits;
    bitNumber -= bits;

    return result;
  }

  private Node getNode() {
    Node node;
    if (nodes == null) node = new Node();
    else {
      node = nodes;
      nodes = nodes.removeFromList();
    }
    return node;
  }

  private void destroyTree(Node root) {
    if (nodes == null) nodes = root;
    else nodes.joinList(root);
    this.root = null;
    sorted2.clear();
  }

  private void insertNode(Node node) {
    Map.Entry<Integer, Node> test2 = sorted2.ceilingEntry(node.probability);
    Node current;


    if (test2 != null) {
      current = test2.getValue();
      node.insertAfter(current);
    } else {
      if (root != null) {
        node.insertAfter(root.prev);
      } else {
        node.newList();
      }
      root = node;
    }

    sorted2.put(node.probability, node);
  }

  private Node addValueToTree(int value) {
    // create leaf node
    Node node = getNode();
    node.value = value;
    node.probability = 0;
    node.child[0] = null;
    node.child[1] = null;

    insertNode(node);

    // create branch node
    Node node2 = getNode();
    Node child1 = root.prev;
    Node child2 = child1.prev;

    node2.value = -1;
    node2.probability = child1.probability + child2.probability;
    node2.child[0] = child1;
    node2.child[1] = child2;
    node2.parent = child2.parent;

    node2.insertAfter(child2.prev);

    // insert into tree
    if (node2.parent.child[0] == child2) node2.parent.child[0] = node2;
    else node2.parent.child[1] = node2;

    child1.parent = node2;
    child2.parent = node2;

    return node;
  }

  private void incrementProbability(Node node) {
    while (node != null) {
      // possible optimization here. Is all this really nescescary to enforce order?
      if (sorted2.get(node.probability) == node) {
        if (node.probability == node.prev.probability)
          sorted2.put(node.probability, node.prev);
        else
          sorted2.remove(node.probability);
      }
      node.probability += 1;

      Map.Entry<Integer, Node> test2 = sorted2.ceilingEntry(node.probability);
      Node where;
      if (test2 != null) where = test2.getValue().next;
      else where = root;

      if (where != node) {
        node.listSwap(where);
        node.treeSwap(where);

        if (where.probability != where.next.probability) {
          sorted2.put(where.probability, where);
        }
      }
      sorted2.put(node.probability, node);

      node = node.parent;
    }
  }

  private void buildTree(byte tree) {
    final byte[] probabilities = PROBABILITY_TABLES[tree];

    // destroy any existing tree
    if (root != null) destroyTree(root);

    // generate leaves
    for (int i = 0; i < 0x102; i++) {
      int prob = probabilities[i] & 0xFF;

      if (prob == 0) continue;

      Node node = getNode();
      node.value = i;
      node.probability = prob;
      node.child[0] = null;
      node.child[1] = null;

      insertNode(node);
    }

    // generate tree
    Node current = root.prev;
    while (current != root) {
      Node node = getNode();
      Node child1 = current;
      Node child2 = current = current.prev;

      child1.parent = node;
      child2.parent = node;

      node.value = -1;
      node.probability = child1.probability + child2.probability;
      node.child[0] = child1;
      node.child[1] = child2;
      insertNode(node);

      current = current.prev;
    }

    root.parent = null;
  }

  private static final ByteBufAllocator ALLOC = PooledByteBufAllocator.DEFAULT;

  synchronized int decompress(ByteBuf inout) {
    // NOTE: in must be copied because it will eventually reach out
    final ByteBuf in = ALLOC.heapBuffer(inout.resetReaderIndex().readableBytes());
    try {
      in.writeBytes(inout);
      return decompress(in, inout.clear());
    } finally {
      in.release();
    }
  }

  synchronized int decompress(ByteBuf in, ByteBuf out) {
    log.traceEntry("decompress(in: {}, out: {})", in, out);
    setSource(in);
    byte type = (byte) getBits(8);
    buildTree(type);

    boolean adjustProbability = type == 0;

    for (;;) {
      Node current = root;
      while (current.value == -1) {
        final int i = getBits(1);
        current = current.child[i];
      }

      if (current.value == 0x101) {
        int value = getBits(8);
        current = addValueToTree(value);
        incrementProbability(current);
        if (!adjustProbability) incrementProbability(current);
      } else if (current.value == 0x100) {
        break;
      }

      out.writeByte(current.value);

      if (adjustProbability) {
        incrementProbability(current);
      }
    }

    return out.writerIndex();
  }

  private static final byte[][] PROBABILITY_TABLES = {
      // Data for compression type 0x00
      {
          0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x02, 0x01, 0x01
      },

      // Data for compression type 0x01
      {
          0x54, 0x16, 0x16, 0x0D, 0x0C, 0x08, 0x06, 0x05, 0x06, 0x05, 0x06, 0x03, 0x04, 0x04,
          0x03, 0x05, 0x0E, 0x0B, 0x14, 0x13, 0x13, 0x09, 0x0B, 0x06, 0x05, 0x04, 0x03, 0x02,
          0x03, 0x02, 0x02, 0x02, 0x0D, 0x07, 0x09, 0x06, 0x06, 0x04, 0x03, 0x02, 0x04, 0x03,
          0x03, 0x03, 0x03, 0x03, 0x02, 0x02, 0x09, 0x06, 0x04, 0x04, 0x04, 0x04, 0x03, 0x02,
          0x03, 0x02, 0x02, 0x02, 0x02, 0x03, 0x02, 0x04, 0x08, 0x03, 0x04, 0x07, 0x09, 0x05,
          0x03, 0x03, 0x03, 0x03, 0x02, 0x02, 0x02, 0x03, 0x02, 0x02, 0x03, 0x02, 0x02, 0x02,
          0x02, 0x02, 0x02, 0x02, 0x02, 0x01, 0x01, 0x01, 0x02, 0x01, 0x02, 0x02, 0x06, 0x0A,
          0x08, 0x08, 0x06, 0x07, 0x04, 0x03, 0x04, 0x04, 0x02, 0x02, 0x04, 0x02, 0x03, 0x03,
          0x04, 0x03, 0x07, 0x07, 0x09, 0x06, 0x04, 0x03, 0x03, 0x02, 0x01, 0x02, 0x02, 0x02,
          0x02, 0x02, 0x0A, 0x02, 0x02, 0x03, 0x02, 0x02, 0x01, 0x01, 0x02, 0x02, 0x02, 0x06,
          0x03, 0x05, 0x02, 0x03, 0x02, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
          0x01, 0x02, 0x03, 0x01, 0x01, 0x01, 0x02, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x02,
          0x04, 0x04, 0x04, 0x07, 0x09, 0x08, 0x0C, 0x02, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
          0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x02, 0x01, 0x01, 0x03, 0x04, 0x01, 0x02, 0x04,
          0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x02, 0x01, 0x01, 0x01, 0x04, 0x01,
          0x01, 0x01, 0x01, 0x01, 0x02, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
          0x02, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x03, 0x01, 0x01, 0x01, 0x01, 0x01,
          0x01, 0x01, 0x02, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x02, 0x02, 0x01, 0x01, 0x02,
          0x02, 0x02, 0x06, 0x4B, 0x01, 0x01
      },

      // Data for compression type 0x02
      {
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x27, 0x00, 0x00, 0x23,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, (byte) 0xFF, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x02,
          0x02, 0x01, 0x01, 0x06, 0x0E, 0x10, 0x04, 0x06, 0x08, 0x05, 0x04, 0x04, 0x03, 0x03,
          0x02, 0x02, 0x03, 0x03, 0x01, 0x01, 0x02, 0x01, 0x01, 0x01, 0x04, 0x02, 0x04, 0x02,
          0x02, 0x02, 0x01, 0x01, 0x04, 0x01, 0x01, 0x02, 0x03, 0x03, 0x02, 0x03, 0x01, 0x03,
          0x06, 0x04, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x02, 0x01, 0x02, 0x01, 0x01, 0x01,
          0x29, 0x07, 0x16, 0x12, 0x40, 0x0A, 0x0A, 0x11, 0x25, 0x01, 0x03, 0x17, 0x10, 0x26,
          0x2A, 0x10, 0x01, 0x23, 0x23, 0x2F, 0x10, 0x06, 0x07, 0x02, 0x09, 0x01, 0x01, 0x01,
          0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x01
      },

      // Data for compression type 0x03
      {
          (byte) 0xFF, 0x0B, 0x07, 0x05, 0x0B, 0x02, 0x02, 0x02, 0x06, 0x02, 0x02, 0x01, 0x04,
          0x02, 0x01, 0x03, 0x09, 0x01, 0x01, 0x01, 0x03, 0x04, 0x01, 0x01, 0x02, 0x01, 0x01,
          0x01, 0x02, 0x01, 0x01, 0x01, 0x05, 0x01, 0x01, 0x01, 0x0D, 0x01, 0x01, 0x01, 0x01,
          0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x02, 0x01, 0x01, 0x03, 0x01, 0x01, 0x01,
          0x01, 0x01, 0x01, 0x01, 0x02, 0x01, 0x01, 0x01, 0x01, 0x0A, 0x04, 0x02, 0x01, 0x06,
          0x03, 0x02, 0x01, 0x01, 0x01, 0x01, 0x01, 0x03, 0x01, 0x01, 0x01, 0x05, 0x02, 0x03,
          0x04, 0x03, 0x03, 0x03, 0x02, 0x01, 0x01, 0x01, 0x02, 0x01, 0x02, 0x03, 0x03, 0x01,
          0x03, 0x01, 0x01, 0x02, 0x05, 0x01, 0x01, 0x04, 0x03, 0x05, 0x01, 0x03, 0x01, 0x03,
          0x03, 0x02, 0x01, 0x04, 0x03, 0x0A, 0x06, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
          0x01, 0x01, 0x01, 0x02, 0x02, 0x01, 0x0A, 0x02, 0x05, 0x01, 0x01, 0x02, 0x07, 0x02,
          0x17, 0x01, 0x05, 0x01, 0x01, 0x0E, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
          0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
          0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
          0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x06, 0x02, 0x01,
          0x04, 0x05, 0x01, 0x01, 0x02, 0x01, 0x01, 0x01, 0x01, 0x02, 0x01, 0x01, 0x01, 0x01,
          0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
          0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x07, 0x01, 0x01, 0x02, 0x01,
          0x01, 0x01, 0x01, 0x02, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x02, 0x01, 0x01,
          0x01, 0x01, 0x01, 0x01, 0x11, 0x01, 0x01
      },

      // Data for compression type 0x04
      {
          (byte) 0xFF, (byte) 0xFB, (byte) 0x98, (byte) 0x9A, (byte) 0x84, (byte) 0x85, 0x63,
          0x64, 0x3E, 0x3E, 0x22, 0x22, 0x13, 0x13, 0x18, 0x17, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x01
      },

      // Data for compression type 0x05
      {
          (byte) 0xFF, (byte) 0xF1, (byte) 0x9D, (byte) 0x9E, (byte) 0x9A, (byte) 0x9B, (byte)
          0x9A, (byte) 0x97, (byte) 0x93, (byte) 0x93, (byte) 0x8C, (byte) 0x8E, (byte) 0x86,
          (byte) 0x88, (byte) 0x80, (byte) 0x82, 0x7C, 0x7C, 0x72, 0x73, 0x69, 0x6B, 0x5F, 0x60,
          0x55, 0x56, 0x4A, 0x4B, 0x40, 0x41, 0x37, 0x37, 0x2F, 0x2F, 0x27, 0x27, 0x21, 0x21,
          0x1B, 0x1C, 0x17, 0x17, 0x13, 0x13, 0x10, 0x10, 0x0D, 0x0D, 0x0B, 0x0B, 0x09, 0x09,
          0x08, 0x08, 0x07, 0x07, 0x06, 0x05, 0x05, 0x04, 0x04, 0x04, 0x19, 0x18, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x01
      },

      // Data for compression type 0x06
      {
          (byte) 0xC3, (byte) 0xCB, (byte) 0xF5, 0x41, (byte) 0xFF, 0x7B, (byte) 0xF7, 0x21,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          (byte) 0xBF, (byte) 0xCC, (byte) 0xF2, 0x40, (byte) 0xFD, 0x7C, (byte) 0xF7, 0x22,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x7A, 0x46, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x01, 0x01
      },

      // Data for compression type 0x07
      {
          (byte) 0xC3, (byte) 0xD9, (byte) 0xEF, 0x3D, (byte) 0xF9, 0x7C, (byte) 0xE9, 0x1E,
          (byte) 0xFD, (byte) 0xAB, (byte) 0xF1, 0x2C, (byte) 0xFC, 0x5B, (byte) 0xFE, 0x17,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xBD, (byte) 0xD9, (byte) 0xEC, 0x3D, (byte)
          0xF5, 0x7D, (byte) 0xE8, 0x1D, (byte) 0xFB, (byte) 0xAE, (byte) 0xF0, 0x2C, (byte)
          0xFB, 0x5C, (byte) 0xFF, 0x18, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x70, 0x6C, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x01
      },

      // Data for compression type 0x08
      {
          (byte) 0xBA, (byte) 0xC5, (byte) 0xDA, 0x33, (byte) 0xE3, 0x6D, (byte) 0xD8, 0x18,
          (byte) 0xE5, (byte) 0x94, (byte) 0xDA, 0x23, (byte) 0xDF, 0x4A, (byte) 0xD1, 0x10,
          (byte) 0xEE, (byte) 0xAF, (byte) 0xE4, 0x2C, (byte) 0xEA, 0x5A, (byte) 0xDE, 0x15,
          (byte) 0xF4, (byte) 0x87, (byte) 0xE9, 0x21, (byte) 0xF6, 0x43, (byte) 0xFC, 0x12,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, (byte) 0xB0, (byte) 0xC7, (byte) 0xD8, 0x33, (byte) 0xE3, 0x6B,
          (byte) 0xD6, 0x18, (byte) 0xE7, (byte) 0x95, (byte) 0xD8, 0x23, (byte) 0xDB, 0x49,
          (byte) 0xD0, 0x11, (byte) 0xE9, (byte) 0xB2, (byte) 0xE2, 0x2B, (byte) 0xE8, 0x5C,
          (byte) 0xDD, 0x15, (byte) 0xF1, (byte) 0x87, (byte) 0xE7, 0x20, (byte) 0xF7, 0x44,
          (byte) 0xFF, 0x13, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x5F, (byte) 0x9E, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x01
      }
  };
}
