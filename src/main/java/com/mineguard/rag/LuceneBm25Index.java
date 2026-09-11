package com.mineguard.rag;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 基于 Lucene BM25 的进程内关键词索引，额外保留设备号和算法 ID 的精确词。 */
public final class LuceneBm25Index implements AutoCloseable {
    private static final String TITLE = "title";
    private static final String CONTENT = "content";
    private static final String EXACT = "exact";
    private static final Pattern EXACT_TERM = Pattern.compile(
            "(?iu)(?:camera|device|sensor)-[a-z0-9-]+|[a-z][a-z0-9]*(?:_[a-z0-9]+)+");
    private final Analyzer analyzer = new CJKAnalyzer();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private State state;

    public void replaceAll(List<DocumentChunk> chunks) {
        State next = build(chunks);
        lock.writeLock().lock();
        try {
            State previous = state;
            state = next;
            if (previous != null) previous.close();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<LexicalMatch> search(String queryText, int topK) {
        if (queryText == null || queryText.isBlank()) throw new IllegalArgumentException("BM25 查询不能为空");
        if (topK < 1) throw new IllegalArgumentException("topK 必须为正数");
        Query query = buildQuery(queryText);
        lock.readLock().lock();
        try {
            if (state == null) return List.of();
            List<LexicalMatch> matches = new ArrayList<>();
            for (ScoreDoc score : state.searcher().search(query, topK).scoreDocs) {
                Document document = state.searcher().storedFields().document(score.doc);
                matches.add(new LexicalMatch(new DocumentChunk(
                        document.get("documentId"), document.get(TITLE), document.get("chunkId"), document.get(CONTENT)),
                        score.score));
            }
            return List.copyOf(matches);
        } catch (IOException ex) {
            throw new IllegalStateException("Lucene BM25 检索失败", ex);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return state == null ? 0 : state.reader().numDocs();
        } finally {
            lock.readLock().unlock();
        }
    }

    private State build(List<DocumentChunk> chunks) {
        Directory directory = new ByteBuffersDirectory();
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer)
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                    .setSimilarity(new BM25Similarity());
            try (IndexWriter writer = new IndexWriter(directory, config)) {
                Set<String> ids = new LinkedHashSet<>();
                for (DocumentChunk chunk : chunks) {
                    if (chunk.chunkId() == null || chunk.chunkId().isBlank() || !ids.add(chunk.chunkId())) {
                        throw new IllegalArgumentException("文档块 ID 必须非空且唯一");
                    }
                    writer.addDocument(toDocument(chunk));
                }
                writer.commit();
            }
            DirectoryReader reader = DirectoryReader.open(directory);
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());
            return new State(directory, reader, searcher);
        } catch (RuntimeException | IOException ex) {
            try { directory.close(); } catch (IOException ignored) {}
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Lucene BM25 索引构建失败", ex);
        }
    }

    private Document toDocument(DocumentChunk chunk) {
        Document document = new Document();
        document.add(new StringField("chunkId", chunk.chunkId(), Field.Store.YES));
        document.add(new StoredField("documentId", chunk.documentId()));
        document.add(new TextField(TITLE, chunk.title(), Field.Store.YES));
        document.add(new TextField(CONTENT, chunk.content(), Field.Store.YES));
        exactTerms(chunk.title() + " " + chunk.content()).forEach(term ->
                document.add(new StringField(EXACT, term, Field.Store.NO)));
        return document;
    }

    private Query buildQuery(String queryText) {
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        for (String token : analyzedTerms(queryText)) {
            query.add(new BoostQuery(new TermQuery(new Term(TITLE, token)), 2.0f), BooleanClause.Occur.SHOULD);
            query.add(new TermQuery(new Term(CONTENT, token)), BooleanClause.Occur.SHOULD);
        }
        for (String exact : exactTerms(queryText)) {
            query.add(new BoostQuery(new TermQuery(new Term(EXACT, exact)), 4.0f), BooleanClause.Occur.SHOULD);
        }
        BooleanQuery built = query.build();
        return built.clauses().isEmpty() ? new MatchNoDocsQuery() : built;
    }

    private List<String> analyzedTerms(String value) {
        List<String> terms = new ArrayList<>();
        try (TokenStream tokens = analyzer.tokenStream(CONTENT, new StringReader(value))) {
            CharTermAttribute term = tokens.addAttribute(CharTermAttribute.class);
            tokens.reset();
            while (tokens.incrementToken()) terms.add(term.toString());
            tokens.end();
            return terms;
        } catch (IOException ex) {
            throw new IllegalStateException("Lucene 查询分词失败", ex);
        }
    }

    private Set<String> exactTerms(String value) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = EXACT_TERM.matcher(value);
        while (matcher.find()) terms.add(matcher.group().toLowerCase(Locale.ROOT));
        return terms;
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (state != null) state.close();
            state = null;
            analyzer.close();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public record LexicalMatch(DocumentChunk chunk, double score) {}

    private record State(Directory directory, DirectoryReader reader, IndexSearcher searcher) {
        void close() {
            try { reader.close(); } catch (IOException ignored) {}
            try { directory.close(); } catch (IOException ignored) {}
        }
    }
}
