/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2017 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.ql.sort;

import com.questdb.misc.Misc;
import com.questdb.misc.Unsafe;
import com.questdb.ql.*;
import com.questdb.ql.join.hash.FakeRecord;
import com.questdb.ql.map.MapUtils;
import com.questdb.ql.ops.AbstractRecordSource;
import com.questdb.std.MemoryPages;
import com.questdb.std.Mutable;
import com.questdb.std.str.CharSink;
import com.questdb.store.factory.ReaderFactory;
import com.questdb.store.factory.configuration.RecordMetadata;

import java.io.Closeable;

public class RBTreeSortedRecordSource extends AbstractRecordSource implements Mutable, RecordSource, Closeable {
    // P(8) + L + R + C(1) + REF + TOP
    private static final int BLOCK_SIZE = 8 + 8 + 8 + 1 + 8 + 8;
    private static final int O_LEFT = 8;
    private static final int O_RIGHT = 16;
    private static final int O_COLOUR = 24;
    private static final int O_REF = 25;
    private static final int O_TOP = 33;

    private static final byte RED = 1;
    private static final byte BLACK = 0;
    private final RecordList recordList;
    private final MemoryPages mem;
    private final RecordComparator comparator;
    private final RecordSource delegate;
    private final TreeCursor cursor = new TreeCursor();
    private final FakeRecord fakeRecord = new FakeRecord();
    private final boolean byRowId;
    private final Record sourceRecord;
    private long root = -1;
    private RecordCursor sourceCursor;

    public RBTreeSortedRecordSource(RecordSource delegate, RecordComparator comparator, int keyPageSize, int valuePageSize) {
        this.delegate = delegate;
        this.comparator = comparator;
        this.mem = new MemoryPages(keyPageSize);
        this.byRowId = delegate.supportsRowIdAccess();
        this.recordList = new RecordList(byRowId ? MapUtils.ROWID_RECORD_METADATA : delegate.getMetadata(), valuePageSize);
        this.sourceRecord = delegate.newRecord();
    }

    @Override
    public void clear() {
        root = -1;
        this.mem.clear();
        recordList.clear();
    }

    @Override
    public void close() {
        Misc.free(delegate);
        Misc.free(recordList);
        Misc.free(mem);
    }

    @Override
    public RecordMetadata getMetadata() {
        return delegate.getMetadata();
    }

    @Override
    public RecordCursor prepareCursor(ReaderFactory factory, CancellationHandler cancellationHandler) {
        clear();
        setSourceCursor(delegate.prepareCursor(factory, cancellationHandler));
        if (byRowId) {
            buildMapByRowId(sourceCursor, cancellationHandler);
        } else {
            buildMap(sourceCursor, cancellationHandler);
        }
        return setupCursor();
    }

    @Override
    public boolean supportsRowIdAccess() {
        return true;
    }

    @Override
    public Record getRecord() {
        return byRowId ? sourceRecord : recordList.getRecord();
    }

    public void put(Record record) {
        if (root == -1) {
            putParent(record);
            return;
        }

        comparator.setLeft(record);

        long p = root;
        long parent;
        int cmp;
        do {
            parent = p;
            long r = refOf(p);
            cmp = comparator.compare(recordList.recordAt(r));
            if (cmp < 0) {
                p = leftOf(p);
            } else if (cmp > 0) {
                p = rightOf(p);
            } else {
                setRef(p, recordList.append(record, r));
                return;
            }
        } while (p > -1);

        p = allocateBlock();
        setParent(p, parent);
        long r = recordList.append(record, (long) -1);
        setTop(p, r);
        setRef(p, r);

        if (cmp < 0) {
            setLeft(parent, p);
        } else {
            setRight(parent, p);
        }
        fix(p);
    }

    public void put(long rowId) {
        if (root == -1) {
            putParent(fakeRecord.of(rowId));
            return;
        }

        sourceCursor.recordAt(sourceRecord, rowId);
        comparator.setLeft(sourceRecord);

        long p = root;
        long parent;
        int cmp;
        do {
            parent = p;
            long r = refOf(p);
            sourceCursor.recordAt(sourceRecord, recordList.recordAt(r).getLong(0));
            cmp = comparator.compare(sourceRecord);
            if (cmp < 0) {
                p = leftOf(p);
            } else if (cmp > 0) {
                p = rightOf(p);
            } else {
                setRef(p, recordList.append(fakeRecord.of(rowId), r));
                return;
            }
        } while (p > -1);

        p = allocateBlock();
        setParent(p, parent);
        long r = recordList.append(fakeRecord.of(rowId), (long) -1);
        setTop(p, r);
        setRef(p, r);

        if (cmp < 0) {
            setLeft(parent, p);
        } else {
            setRight(parent, p);
        }
        fix(p);
    }

    public RecordCursor setupCursor() {
        cursor.setup();
        return cursor;
    }

    @Override
    public void toSink(CharSink sink) {
        sink.put('{');
        sink.putQuoted("op").put(':').putQuoted("RBTreeSortedRecordSource").put(',');
        sink.putQuoted("byRowId").put(':').put(byRowId).put(',');
        sink.putQuoted("src").put(':').put(delegate);
        sink.put('}');
    }

    private static void setLeft(long blockAddress, long left) {
        Unsafe.getUnsafe().putLong(blockAddress + O_LEFT, left);
    }

    private static long rightOf(long blockAddress) {
        return blockAddress == -1 ? -1 : Unsafe.getUnsafe().getLong(blockAddress + O_RIGHT);
    }

    private static long leftOf(long blockAddress) {
        return blockAddress == -1 ? -1 : Unsafe.getUnsafe().getLong(blockAddress + O_LEFT);
    }

    private static void setParent(long blockAddress, long parent) {
        Unsafe.getUnsafe().putLong(blockAddress, parent);
    }

    private static long refOf(long blockAddress) {
        return blockAddress == -1 ? -1 : Unsafe.getUnsafe().getLong(blockAddress + O_REF);
    }

    private static long topOf(long blockAddress) {
        return blockAddress == -1 ? -1 : Unsafe.getUnsafe().getLong(blockAddress + O_TOP);
    }

    private static void setRef(long blockAddress, long recRef) {
        Unsafe.getUnsafe().putLong(blockAddress + O_REF, recRef);
    }

    private static void setTop(long blockAddress, long recRef) {
        Unsafe.getUnsafe().putLong(blockAddress + O_TOP, recRef);
    }

    private static void setRight(long blockAddress, long right) {
        Unsafe.getUnsafe().putLong(blockAddress + O_RIGHT, right);
    }

    private static long parentOf(long blockAddress) {
        return blockAddress == -1 ? -1 : Unsafe.getUnsafe().getLong(blockAddress);
    }

    private static long parent2Of(long blockAddress) {
        return parentOf(parentOf(blockAddress));
    }

    private static void setColor(long blockAddress, byte colour) {
        if (blockAddress == -1) {
            return;
        }
        Unsafe.getUnsafe().putByte(blockAddress + O_COLOUR, colour);
    }

    private static byte colorOf(long blockAddress) {
        return blockAddress == -1 ? BLACK : Unsafe.getUnsafe().getByte(blockAddress + O_COLOUR);
    }

    private static long successor(long current) {
        long p = rightOf(current);
        if (p != -1) {
            long l;
            while ((l = leftOf(p)) != -1) {
                p = l;
            }
        } else {
            p = parentOf(current);
            long ch = current;
            while (p != -1 && ch == rightOf(p)) {
                ch = p;
                p = parentOf(p);
            }
        }
        return p;
    }

    @Override
    public Record newRecord() {
        return byRowId ? delegate.newRecord() : recordList.newRecord();
    }

    private long allocateBlock() {
        long p = mem.allocate(BLOCK_SIZE);
        setLeft(p, -1);
        setRight(p, -1);
        setColor(p, BLACK);
        return p;
    }

    private void buildMap(RecordCursor cursor, CancellationHandler cancellationHandler) {
        while (cursor.hasNext()) {
            cancellationHandler.check();
            put(cursor.next());
        }
    }

    private void buildMapByRowId(RecordCursor cursor, CancellationHandler cancellationHandler) {
        while (cursor.hasNext()) {
            cancellationHandler.check();
            put(cursor.next().getRowId());
        }
    }

    private void fix(long x) {
        setColor(x, RED);

        while (x != -1 && x != root && colorOf(parentOf(x)) == RED) {
            if (parentOf(x) == leftOf(parent2Of(x))) {
                long y = rightOf(parent2Of(x));
                if (colorOf(y) == RED) {
                    setColor(parentOf(x), BLACK);
                    setColor(y, BLACK);
                    setColor(parent2Of(x), RED);
                    x = parent2Of(x);
                } else {
                    if (x == rightOf(parentOf(x))) {
                        x = parentOf(x);
                        rotateLeft(x);
                    }
                    setColor(parentOf(x), BLACK);
                    setColor(parent2Of(x), RED);
                    rotateRight(parent2Of(x));
                }
            } else {
                long y = leftOf(parent2Of(x));
                if (colorOf(y) == RED) {
                    setColor(parentOf(x), BLACK);
                    setColor(y, BLACK);
                    setColor(parent2Of(x), RED);
                    x = parent2Of(x);
                } else {
                    if (x == leftOf(parentOf(x))) {
                        x = parentOf(x);
                        rotateRight(x);
                    }
                    setColor(parentOf(x), BLACK);
                    setColor(parent2Of(x), RED);
                    rotateLeft(parent2Of(x));
                }
            }
        }
        setColor(root, BLACK);
    }

    private void putParent(Record record) {
        root = allocateBlock();
        long r = recordList.append(record, -1L);
        setTop(root, r);
        setRef(root, r);
        setParent(root, -1);
        setLeft(root, -1);
        setRight(root, -1);
    }

    private void rotateLeft(long p) {
        if (p != -1) {
            long r = rightOf(p);
            setRight(p, leftOf(r));
            if (leftOf(r) != -1) {
                setParent(leftOf(r), p);
            }
            setParent(r, parentOf(p));
            if (parentOf(p) == -1) {
                root = r;
            } else if (leftOf(parentOf(p)) == p) {
                setLeft(parentOf(p), r);
            } else {
                setRight(parentOf(p), r);
            }
            setLeft(r, p);
            setParent(p, r);
        }
    }

    private void rotateRight(long p) {
        if (p != -1) {
            long l = leftOf(p);
            setLeft(p, rightOf(l));
            if (rightOf(l) != -1) {
                setParent(rightOf(l), p);
            }
            setParent(l, parentOf(p));
            if (parentOf(p) == -1) {
                root = l;
            } else if (rightOf(parentOf(p)) == p) {
                setRight(parentOf(p), l);
            } else {
                setLeft(parentOf(p), l);
            }
            setRight(l, p);
            setParent(p, l);
        }
    }

    private void setSourceCursor(RecordCursor sourceCursor) {
        this.sourceCursor = sourceCursor;
        recordList.setStorageFacade(sourceCursor.getStorageFacade());
    }

    private class TreeCursor implements RecordCursor, com.questdb.std.ImmutableIterator<Record> {

        private long current;

        @Override
        public StorageFacade getStorageFacade() {
            return recordList.getStorageFacade();
        }

        @Override
        public Record recordAt(long rowId) {
            return byRowId ?
                    sourceCursor.recordAt(rowId) :
                    recordList.recordAt(rowId);
        }

        @Override
        public void recordAt(Record record, long atRowId) {
            if (byRowId) {
                sourceCursor.recordAt(record, atRowId);
            } else {
                recordList.recordAt(record, atRowId);
            }
        }

        @Override
        public void releaseCursor() {
            sourceCursor.releaseCursor();
            recordList.releaseCursor();
        }

        @Override
        public void toTop() {
            setup();
            sourceCursor.toTop();
        }

        @Override
        public boolean hasNext() {
            if (recordList.hasNext()) {
                return true;
            }

            current = successor(current);
            if (current == -1) {
                return false;
            }

            recordList.of(topOf(current));
            return true;
        }

        @Override
        public Record next() {
            final Record underlying = recordList.next();
            if (byRowId) {
                sourceCursor.recordAt(sourceRecord, underlying.getLong(0));
                return sourceRecord;
            }
            return underlying;
        }

        private void setup() {
            long p = root;
            if (p != -1) {
                while (leftOf(p) != -1) {
                    p = leftOf(p);
                }
            }
            recordList.of(topOf(current = p));
        }

        @Override
        public Record getRecord() {
            return RBTreeSortedRecordSource.this.getRecord();
        }

        @Override
        public Record newRecord() {
            return RBTreeSortedRecordSource.this.newRecord();
        }
    }




}