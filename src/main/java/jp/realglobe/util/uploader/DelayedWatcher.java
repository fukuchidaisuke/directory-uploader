package jp.realglobe.util.uploader;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import jp.realglobe.lib.container.Pair;
import jp.realglobe.lib.util.StackTraces;

/**
 * ディレクトリを監視する。
 * 実際に報告するまで間を空けることで、ファイルコピーを 1 つのイベントとして受け取ることを試みる
 */
final class DelayedWatcher implements Runnable {

    private static final Logger LOG = Logger.getLogger(DelayedWatcher.class.getName());

    private final Path target;
    private final long delay;
    private final DelayedWatcher.Callback callback;

    private final History history;

    DelayedWatcher(final Path target, final long delay, final DelayedWatcher.Callback callback) {
        this.target = target;
        this.delay = delay;
        this.callback = callback;

        this.history = new History();
    }

    @Override
    public void run() {
        try (final WatchService watcher = FileSystems.getDefault().newWatchService()) {
            this.target.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);

            while (true) {
                final long waitTime;
                if (this.history.isEmpty()) {
                    waitTime = Long.MAX_VALUE;
                } else {
                    waitTime = this.history.getOldestDate() + this.delay - System.currentTimeMillis();
                }

                final WatchKey key;
                try {
                    key = watcher.poll(waitTime, TimeUnit.MILLISECONDS);
                } catch (final InterruptedException e) {
                    // 終了
                    break;
                }

                final long current = System.currentTimeMillis();

                // できたてほやほやのイベントを登録
                if (key != null) {

                    final Set<Path> names = new HashSet<>();
                    for (final WatchEvent<?> event : key.pollEvents()) {
                        final WatchEvent.Kind<?> kind = event.kind();

                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            LOG.info("Event overflowed");
                            continue;
                        }

                        final Object context = event.context();
                        if (!(context instanceof Path)) {
                            LOG.warning("Not path");
                            continue;
                        }

                        names.add((Path) context);
                    }
                    if (!key.reset()) {
                        throw new RuntimeException("Reset error");
                    }

                    for (final Path name : names) {
                        this.history.add(name, current);
                    }
                }

                // 時期の来たイベントを処理
                while (true) {
                    final Path name = this.history.popOlder(current - this.delay);
                    if (name == null) {
                        break;
                    }

                    final Path path = this.target.resolve(name);
                    LOG.fine("Call callback for " + path);
                    try {
                        this.callback.call(path);
                    } catch (final Exception e) {
                        LOG.warning("Callback failed: " + e);
                        LOG.finest(StackTraces.getString(e));
                    }
                }
            }
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static class History {

        private final PriorityQueue<Pair<Long, Path>> dateToPathQueue;
        private final Map<Path, Long> pathToLastDate;

        History() {
            this.dateToPathQueue = new PriorityQueue<>(new Comparator<Pair<Long, Path>>() {
                @Override
                public int compare(final Pair<Long, Path> o1, final Pair<Long, Path> o2) {
                    final long date1 = o1.getFirst();
                    final long date2 = o2.getFirst();
                    if (date1 < date2) {
                        return -1;
                    } else if (date1 > date2) {
                        return 1;
                    } else {
                        return o1.getSecond().compareTo(o2.getSecond());
                    }
                }
            });
            this.pathToLastDate = new HashMap<>();
        }

        /**
         * 空かどうか
         * @return 空なら true
         */
        public boolean isEmpty() {
            return this.pathToLastDate.isEmpty();
        }

        /**
         * 一番古い日時を返す
         * @return 一番古い日付（ミリ秒）
         * @throws NullPointerException 空のとき
         */
        public long getOldestDate() throws NullPointerException {
            trim();
            return this.dateToPathQueue.peek().getFirst();
        }

        /**
         * より古いパスを返す
         * @param date 基準日時
         * @return 基準日時より古いパス。
         *         無ければ null
         */
        public Path popOlder(final long date) {
            trim();
            final Pair<Long, Path> oldest = this.dateToPathQueue.peek();
            if (oldest == null) {
                return null;
            } else if (oldest.getFirst() >= date) {
                return null;
            }
            this.dateToPathQueue.poll();
            this.pathToLastDate.remove(oldest.getSecond());
            return oldest.getSecond();
        }

        /**
         * パスを加える。
         * 既にある場合は日時を上書きする
         * @param path パス
         * @param date 日時
         */
        public void add(final Path path, final long date) {
            trim();
            this.dateToPathQueue.add(new Pair<>(date, path));
            this.pathToLastDate.put(path, date);
        }

        /**
         * 紐付かないエントリを消す
         */
        private void trim() {
            while (true) {
                final Pair<Long, Path> oldest = this.dateToPathQueue.peek();
                if (oldest == null) {
                    return;
                }
                final Long date = this.pathToLastDate.get(oldest.getSecond());
                if (date != null && date.equals(oldest.getFirst())) {
                    break;
                }
                this.dateToPathQueue.poll();
            }
        }

    }

    /**
     * 作成・変更されたファイルのパスを受け取る関数
     */
    static interface Callback {
        /**
         * @param path 作成・変更されたファイルのパス
         * @throws Exception エラー
         */
        void call(Path path) throws Exception;
    }

}
