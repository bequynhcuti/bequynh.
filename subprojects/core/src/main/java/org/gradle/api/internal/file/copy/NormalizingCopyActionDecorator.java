/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.file.copy;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.Transformer;
import org.gradle.api.file.ConfigurableFilePermissions;
import org.gradle.api.file.ContentFilterable;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.ExpandDetails;
import org.gradle.api.file.FilePermissions;
import org.gradle.api.file.RelativePath;
import org.gradle.api.file.SymbolicLinkDetails;
import org.gradle.api.internal.file.AbstractFileTreeElement;
import org.gradle.api.internal.file.CopyActionProcessingStreamAction;
import org.gradle.api.tasks.WorkResult;
import org.gradle.internal.file.Chmod;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FilterReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link CopyAction} which cleans up the tree as it is visited. Removes duplicate directories and adds in missing directories. Removes empty directories if instructed to do so by copy
 * spec.
 */
public class NormalizingCopyActionDecorator implements CopyAction {

    private final CopyAction delegate;
    private final Chmod chmod;

    public NormalizingCopyActionDecorator(CopyAction delegate, Chmod chmod) {
        this.delegate = delegate;
        this.chmod = chmod;
    }

    @Override
    public WorkResult execute(final CopyActionProcessingStream stream) {
        final Set<RelativePath> visitedDirs = new HashSet<>();
        final ListMultimap<RelativePath, FileCopyDetailsInternal> pendingDirs = ArrayListMultimap.create();

        return delegate.execute(action -> {
            stream.process(details -> {
                if (details.isDirectory()) {
                    RelativePath path = details.getRelativePath();
                    if (!visitedDirs.contains(path)) {
                        pendingDirs.put(path, details);
                    }
                } else {
                    maybeVisit(details.getRelativePath().getParent(), details.isIncludeEmptyDirs(), action, visitedDirs, pendingDirs);
                    action.processFile(details);
                }
            });

            for (RelativePath path : new LinkedHashSet<>(pendingDirs.keySet())) {
                List<FileCopyDetailsInternal> detailsList = new ArrayList<>(pendingDirs.get(path));
                for (FileCopyDetailsInternal details : detailsList) {
                    if (details.isIncludeEmptyDirs()) {
                        maybeVisit(path, details.isIncludeEmptyDirs(), action, visitedDirs, pendingDirs);
                    }
                }
            }

            visitedDirs.clear();
            pendingDirs.clear();
        });
    }

    private void maybeVisit(@Nullable RelativePath path, boolean includeEmptyDirs, CopyActionProcessingStreamAction delegateAction, Set<RelativePath> visitedDirs, ListMultimap<RelativePath, FileCopyDetailsInternal> pendingDirs) {
        if (path == null || path.getParent() == null || !visitedDirs.add(path)) {
            return;
        }
        maybeVisit(path.getParent(), includeEmptyDirs, delegateAction, visitedDirs, pendingDirs);
        List<FileCopyDetailsInternal> detailsForPath = pendingDirs.removeAll(path);

        FileCopyDetailsInternal dir;
        if (detailsForPath.isEmpty()) {
            // TODO - this is pretty nasty, look at avoiding using a time bomb stub here
            dir = new ParentDirectoryStub(path, includeEmptyDirs, chmod);
        } else {
            dir = detailsForPath.get(0);
        }
        delegateAction.processFile(dir);
    }

    private static class ParentDirectoryStub extends AbstractFileTreeElement implements FileCopyDetailsInternal {
        private final RelativePath path;
        private final boolean includeEmptyDirs;
        private long lastModified = System.currentTimeMillis();

        private ParentDirectoryStub(RelativePath path, boolean includeEmptyDirs, Chmod chmod) {
            super(chmod);
            this.path = path;
            this.includeEmptyDirs = includeEmptyDirs;
        }

        @Override
        public boolean isIncludeEmptyDirs() {
            return includeEmptyDirs;
        }

        @Override
        public String getDisplayName() {
            return path.toString();
        }

        @Override
        public File getFile() {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public boolean isDirectory() {
            return !path.isFile();
        }

        @Override
        public long getLastModified() {
            return lastModified;
        }

        @Override
        public long getSize() {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public InputStream open() {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public RelativePath getRelativePath() {
            return path;
        }

        @Nullable
        @Override
        public SymbolicLinkDetails getSymbolicLinkDetails() {
            return null; //this is not a symlink because it was traversed from below
        }

        @Override
        public void exclude() {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public void setName(String name) {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public void setPath(String path) {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public void setRelativePath(RelativePath path) {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public void setMode(int mode) {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public void permissions(Action<? super ConfigurableFilePermissions> configureAction) {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public void setPermissions(FilePermissions permissions) {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public void setDuplicatesStrategy(DuplicatesStrategy strategy) {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public DuplicatesStrategy getDuplicatesStrategy() {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public boolean isDefaultDuplicatesStrategy() {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public String getSourceName() {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public String getSourcePath() {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public RelativePath getRelativeSourcePath() {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public ContentFilterable filter(Map<String, ?> properties, Class<? extends FilterReader> filterType) {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public ContentFilterable filter(Class<? extends FilterReader> filterType) {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public ContentFilterable filter(Closure closure) {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public ContentFilterable filter(Transformer<String, String> transformer) {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public ContentFilterable expand(Map<String, ?> properties) {
            throw new UnsupportedOperationException("this is a stub");
        }

        @Override
        public ContentFilterable expand(Map<String, ?> properties, Action<? super ExpandDetails> action) {
            throw new UnsupportedOperationException("this is a stub");
        }
    }
}
