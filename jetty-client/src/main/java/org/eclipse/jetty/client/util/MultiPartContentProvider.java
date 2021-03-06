//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client.util;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A {@link ContentProvider} for form uploads with the {@code "multipart/form-data"}
 * content type.</p>
 * <p>Example usage:</p>
 * <pre>
 * MultiPartContentProvider multiPart = new MultiPartContentProvider();
 * multiPart.addPart(new MultiPartContentProvider.FieldPart("field", "foo", StandardCharsets.UTF_8));
 * multiPart.addPart(new MultiPartContentProvider.PathPart("icon", Paths.get("/tmp/img.png"), "image/png"));
 * ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
 *         .method(HttpMethod.POST)
 *         .content(multiPart)
 *         .send();
 * </pre>
 * <p>The above example would be the equivalent of submitting this form:</p>
 * <pre>
 * &lt;form method="POST" enctype="multipart/form-data"  accept-charset="UTF-8"&gt;
 *     &lt;input type="text" name="field" value="foo" /&gt;
 *     &lt;input type="file" name="icon" /&gt;
 * &lt;/form&gt;
 * </pre>
 */
public class MultiPartContentProvider implements ContentProvider.Typed
{
    private static final Logger LOG = Log.getLogger(MultiPartContentProvider.class);
    private static final byte[] COLON_SPACE_BYTES = new byte[]{':', ' '};
    private static final byte[] CR_LF_BYTES = new byte[]{'\r', '\n'};

    private final List<Part> parts = new ArrayList<>();
    private final ByteBuffer firstBoundary;
    private final ByteBuffer middleBoundary;
    private final ByteBuffer onlyBoundary;
    private final ByteBuffer lastBoundary;
    private final String contentType;
    private long length;

    public MultiPartContentProvider()
    {
        this(makeBoundary());
    }

    public MultiPartContentProvider(String boundary)
    {
        this.contentType = "multipart/form-data; boundary=" + boundary;
        String firstBoundaryLine = "--" + boundary + "\r\n";
        this.firstBoundary = ByteBuffer.wrap(firstBoundaryLine.getBytes(StandardCharsets.US_ASCII));
        String middleBoundaryLine = "\r\n" + firstBoundaryLine;
        this.middleBoundary = ByteBuffer.wrap(middleBoundaryLine.getBytes(StandardCharsets.US_ASCII));
        String onlyBoundaryLine = "--" + boundary + "--\r\n";
        this.onlyBoundary = ByteBuffer.wrap(onlyBoundaryLine.getBytes(StandardCharsets.US_ASCII));
        String lastBoundaryLine = "\r\n" + onlyBoundaryLine;
        this.lastBoundary = ByteBuffer.wrap(lastBoundaryLine.getBytes(StandardCharsets.US_ASCII));
        this.length = this.onlyBoundary.remaining();
    }

    private static String makeBoundary()
    {
        Random random = new Random();
        StringBuilder builder = new StringBuilder("JettyHttpClientBoundary");
        int length = builder.length();
        while (builder.length() < length + 16)
        {
            long rnd = random.nextLong();
            builder.append(Long.toString(rnd < 0 ? -rnd : rnd, 36));
        }
        builder.setLength(length + 16);
        return builder.toString();
    }

    /**
     * Adds the given {@code part}.
     * @param part the part to add
     */
    public void addPart(Part part)
    {
        parts.add(part);
        length += length(part);
        if (LOG.isDebugEnabled())
            LOG.debug("Added {}", part);
    }

    /**
     * Removes the given {@code part}.
     * @param part the part to remove
     * @return whether the part was removed
     */
    public boolean remove(Part part)
    {
        boolean removed = parts.remove(part);
        if (removed)
        {
            length -= length(part);
            if (LOG.isDebugEnabled())
                LOG.debug("Removed {}", part);
        }
        return removed;
    }

    private long length(Part part)
    {
        // For the first part, length = firstBoundary + \r\n after the
        // value, which is equal to the length of the middleBoundary.
        return middleBoundary.remaining() + part.getLength();
    }

    @Override
    public long getLength()
    {
        return length;
    }

    @Override
    public Iterator<ByteBuffer> iterator()
    {
        return new MultiPartIterator(parts);
    }

    @Override
    public String getContentType()
    {
        return contentType;
    }

    private static ByteBuffer convertFields(String contentDisposition, Fields fields)
    {
        try
        {
            if (fields == null || fields.isEmpty())
            {
                contentDisposition += "\r\n";
                return ByteBuffer.wrap(contentDisposition.getBytes(StandardCharsets.US_ASCII));
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream((fields.getSize() + 1) * contentDisposition.length());
            buffer.write(contentDisposition.getBytes(StandardCharsets.US_ASCII));
            for (Fields.Field field : fields)
            {
                buffer.write(field.getName().getBytes(StandardCharsets.US_ASCII));
                buffer.write(COLON_SPACE_BYTES);
                buffer.write(field.getValue().getBytes(StandardCharsets.UTF_8));
                buffer.write(CR_LF_BYTES);
            }
            buffer.write(CR_LF_BYTES);
            return ByteBuffer.wrap(buffer.toByteArray());
        }
        catch (IOException x)
        {
            throw new RuntimeIOException(x);
        }
    }

    private class MultiPartIterator implements Iterator<ByteBuffer>, Closeable
    {
        private final List<Part> parts;
        private final Iterator<ByteBuffer> iterator;

        private MultiPartIterator(List<Part> parts)
        {
            this.parts = parts;
            if (parts.isEmpty())
            {
                iterator = Stream.of(onlyBoundary.slice()).iterator();
            }
            else
            {
                List<ByteBuffer> list = new ArrayList<>();
                for (int i = 0; i < parts.size(); ++i)
                {
                    Part part = parts.get(i);
                    if (i == 0)
                        list.add(firstBoundary.slice());
                    else
                        list.add(middleBoundary.slice());
                    list.addAll(part.getByteBuffers());
                }
                list.add(lastBoundary.slice());
                iterator = list.iterator();
            }
        }

        @Override
        public boolean hasNext()
        {
            return iterator.hasNext();
        }

        @Override
        public ByteBuffer next()
        {
            return iterator.next();
        }

        @Override
        public void close() throws IOException
        {
            parts.stream().forEach(Part::close);
        }
    }

    /**
     * A generic part of a multipart form.
     */
    public interface Part extends Closeable
    {
        /**
         * @return the length (in bytes) of this part, including headers and content
         */
        long getLength();

        /**
         * @return the list of {@code ByteBuffers} representing headers and content of this part
         */
        List<ByteBuffer> getByteBuffers();

        /**
         * Closes this part.
         */
        @Override
        default void close()
        {
        }
    }

    /**
     * A {@link Part} for text (or binary) fields.
     */
    public static class FieldPart implements Part
    {
        private final List<ByteBuffer> buffers = new ArrayList<>();
        private final String name;
        private final long length;

        /**
         * <p>Creates a {@code FieldPart} with the given name and the given {@code value}
         * to be encoded using the given {@code charset}.</p>
         * <p>The {@code Content-Type} header will be {@code "text/plain; charset=&lt;charset_name&gt;"},
         * with the {@code charset} name.</p>
         *
         * @param name the part name
         * @param value the part content
         * @param charset the charset to encode the part content
         */
        public FieldPart(String name, String value, Charset charset)
        {
            this(name, charset == null ? Charset.defaultCharset().encode(value) : charset.encode(value), makeContentType(charset));
        }

        /**
         * <p>Creates a {@code FieldPart} with the given name and the given {@code value}.</p>
         * <p>All the headers (apart {@code "Content-Disposition"}) are specified in the given
         * {@code fields} parameter.</p>
         *
         * @param name the part name
         * @param value the part content
         * @param fields the headers associated with this part
         */
        public FieldPart(String name, ByteBuffer value, Fields fields)
        {
            this.name = name;
            buffers.add(convertFields(name, fields));
            buffers.add(value);
            length = buffers.stream().mapToInt(Buffer::remaining).sum();
        }

        private static Fields makeContentType(Charset encoding)
        {
            if (encoding == null)
                return null;
            Fields fields = new Fields();
            fields.put("Content-Type", "text/plain; charset=" + encoding.name());
            return fields;
        }

        @Override
        public long getLength()
        {
            return length;
        }

        @Override
        public List<ByteBuffer> getByteBuffers()
        {
            return buffers;
        }

        private static ByteBuffer convertFields(String name, Fields fields)
        {
            String contentDisposition = "Content-Disposition: form-data; name=\"" + name + "\"\r\n";
            return MultiPartContentProvider.convertFields(contentDisposition, fields);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), name);
        }
    }

    /**
     * <p>A {@link Part} for file fields.</p>
     * <p>Files will be transmitted via {@link FileChannel#map(FileChannel.MapMode, long, long) mapping}
     * and closed when the whole content has been sent.</p>
     */
    public static class PathPart implements Part
    {
        private final List<ByteBuffer> buffers = new ArrayList<>();
        private final Path path;
        private final FileChannel channel;
        private final long length;

        /**
         * <p>Creates a {@code PathPart} with the given {@code name} and referencing the given file
         * {@code path}.</p>
         * <p>The {@code Content-Type} header will default to {@code "application/octet-stream"}.</p>
         *
         * @param name the part name
         * @param path the file path representing the content
         * @throws IOException if an error occurs while trying to open the file path
         */
        public PathPart(String name, Path path) throws IOException
        {
            this(name, path, "application/octet-stream");
        }

        /**
         * <p>Creates a {@code PathPart} withe the given {@code name} and referencing the given file
         * {@code path}, with the specified {@code contentType}.</p>
         *
         * @param name the part name
         * @param path the file path representing the content
         * @param contentType the Content-Type header value
         * @throws IOException if an error occurs while trying to open the file path
         */
        public PathPart(String name, Path path, String contentType) throws IOException
        {
            this(name, path, makeFields(contentType));
        }

        /**
         * <p>Creates a {@code PathPart} withe the given {@code name} and referencing the given file
         * {@code path}.</p>
         * <p>All the headers (apart {@code "Content-Disposition"}) are specified in the given
         * {@code fields} parameter.</p>
         *
         * @param name the part name
         * @param path the file path representing the content
         * @param fields the headers associated with this part
         * @throws IOException if an error occurs while trying to open the file path
         */
        public PathPart(String name, Path path, Fields fields) throws IOException
        {
            this.buffers.add(convertFields(name, path, fields));
            this.path = path;
            this.channel = FileChannel.open(path, StandardOpenOption.READ);
            this.buffers.addAll(fileMap(channel));
            this.length = buffers.stream().mapToInt(Buffer::remaining).sum();
        }

        @Override
        public long getLength()
        {
            return length;
        }

        @Override
        public List<ByteBuffer> getByteBuffers()
        {
            return buffers;
        }

        private static Fields makeFields(String contentType)
        {
            Fields fields = new Fields();
            fields.put("Content-Type", contentType);
            return fields;
        }

        private static ByteBuffer convertFields(String name, Path path, Fields fields)
        {
            String contentDisposition = "Content-Disposition: form-data; name=\"" + name + "\"; " +
                    "filename=\"" + path.getFileName() + "\"\r\n";
            return MultiPartContentProvider.convertFields(contentDisposition, fields);
        }

        private static List<ByteBuffer> fileMap(FileChannel channel) throws IOException
        {
            List<ByteBuffer> result = new ArrayList<>();
            long position = 0;
            long length = channel.size();
            channel.position(position);
            while (length > 0)
            {
                // At most 1 GiB file maps.
                long size = Math.min(1024 * 1024 * 1024, length);
                ByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, position, size);
                result.add(buffer);
                position += size;
                length -= size;
            }
            return result;
        }

        @Override
        public void close()
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Closing {}", this);
                channel.close();
            }
            catch (IOException x)
            {
                LOG.ignore(x);
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), path);
        }
    }
}
