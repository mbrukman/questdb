/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2016 Appsicle
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
 * As a special exception, the copyright holders give permission to link the
 * code of portions of this program with the OpenSSL library under certain
 * conditions as described in each individual source file and distribute
 * linked combinations including the program with the OpenSSL library. You
 * must comply with the GNU Affero General Public License in all respects for
 * all of the code used other than as permitted herein. If you modify file(s)
 * with this exception, you may extend this exception to your version of the
 * file(s), but you are not obligated to do so. If you do not wish to do so,
 * delete this exception statement from your version. If you delete this
 * exception statement from all source files in the program, then also delete
 * it in the license file.
 *
 ******************************************************************************/

package com.nfsdb.misc;

import com.nfsdb.ex.FatalError;
import com.sun.management.OperatingSystemMXBean;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;

@SuppressFBWarnings({"IICU_INCORRECT_INTERNAL_CLASS_USE"})
public final class Os {
    public static final int WINDOWS = 3;
    public static final int _32Bit = -2;
    public static final int type;
    public static final int OSX = 1;
    public static final int LINUX = 2;
    private static final int UNKNOWN = -1;
    private static final OperatingSystemMXBean bean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private Os() {
    }

    public static native int errno();

    public static native int getPid();

    public static long getSystemMemory() {
        return bean.getTotalPhysicalMemorySize();
    }

    @SuppressWarnings("EmptyMethod")
    public static void init() {
    }

    @SuppressFBWarnings("OBL_UNSATISFIED_OBLIGATION")
    private static void loadLib(String lib) {
        InputStream is = Os.class.getResourceAsStream(lib);
        if (is == null) {
            throw new FatalError("Internal error: cannot find " + lib + ", broken package?");
        }

        try {
            File tempLib = null;
            try {
                tempLib = File.createTempFile(lib, ".so");
                // copy to tempLib
                try (FileOutputStream out = new FileOutputStream(tempLib)) {
                    byte[] buf = new byte[4096];
                    while (true) {
                        int read = is.read(buf);
                        if (read == -1) {
                            break;
                        }
                        out.write(buf, 0, read);
                    }
                } finally {
                    tempLib.deleteOnExit();
                }
                System.load(tempLib.getAbsolutePath());
            } catch (IOException e) {
                throw new FatalError("Internal error: cannot unpack " + tempLib, e);
            }
        } finally {
            Misc.free(is);
        }
    }

    static {
        if ("64".equals(System.getProperty("sun.arch.data.model"))) {
            String osName = System.getProperty("os.name");
            if (osName.contains("Linux")) {
                type = LINUX;
                loadLib("/binaries/linux/libnfsdb.so");
            } else if (osName.contains("Mac")) {
                type = OSX;
                loadLib("/binaries/osx/libnfsdb.dylib");
            } else if (osName.contains("Windows")) {
                type = WINDOWS;
                loadLib("/binaries/windows/libnfsdb.dll");
            } else {
                type = UNKNOWN;
            }
        } else {
            type = _32Bit;
        }
    }
}