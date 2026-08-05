import React, {useEffect, useRef, useState} from 'react';
import BrowserOnly from '@docusaurus/BrowserOnly';
import {EditorState} from '@codemirror/state';
import {EditorView, keymap, lineNumbers} from '@codemirror/view';
import {defaultKeymap, history, historyKeymap} from '@codemirror/commands';
import {java} from '@codemirror/lang-java';
import {HighlightStyle, syntaxHighlighting, indentOnInput} from '@codemirror/language';
import {tags as t} from '@lezer/highlight';
import styles from './styles.module.css';

const PISTON_URL = 'https://emkc.org/api/v2/piston/execute';

const blueprintHighlight = HighlightStyle.define([
  {tag: t.keyword, color: '#8ecbff'},
  {tag: [t.string, t.special(t.string)], color: '#ffe08a'},
  {tag: t.comment, color: 'rgba(240, 240, 240, 0.5)', fontStyle: 'italic'},
  {tag: [t.typeName, t.className], color: '#7de89a'},
  {tag: t.number, color: '#ffb08a'},
  {tag: [t.function(t.variableName), t.function(t.propertyName)], color: '#d9c8ff'},
  {tag: t.operator, color: 'var(--white-dim)'},
]);

const blueprintTheme = EditorView.theme(
  {
    '&': {
      backgroundColor: 'var(--blue-deep)',
      color: 'var(--white)',
      fontSize: '.88rem',
    },
    '.cm-content': {
      fontFamily: 'var(--font-mono)',
      padding: '1rem 0',
    },
    '.cm-gutters': {
      backgroundColor: 'var(--blue-deep)',
      color: 'var(--white-dim)',
      border: 'none',
    },
    '&.cm-focused': {outline: 'none'},
    '.cm-activeLine': {backgroundColor: 'rgba(240, 240, 240, 0.06)'},
    '.cm-activeLineGutter': {backgroundColor: 'rgba(240, 240, 240, 0.06)'},
    '.cm-selectionBackground': {backgroundColor: 'rgba(240, 240, 240, 0.18) !important'},
    '.cm-scroller': {fontFamily: 'var(--font-mono)'},
  },
  {dark: true},
);

type Status = 'idle' | 'running' | 'done' | 'error';

function PlaygroundEditor({initialCode, filename}: {initialCode: string; filename: string}) {
  const editorHostRef = useRef<HTMLDivElement | null>(null);
  const viewRef = useRef<EditorView | null>(null);
  const [output, setOutput] = useState<string | null>(null);
  const [status, setStatus] = useState<Status>('idle');

  useEffect(() => {
    if (!editorHostRef.current) return;

    const state = EditorState.create({
      doc: initialCode,
      extensions: [
        lineNumbers(),
        history(),
        indentOnInput(),
        java(),
        syntaxHighlighting(blueprintHighlight),
        keymap.of([...defaultKeymap, ...historyKeymap]),
        blueprintTheme,
      ],
    });

    const view = new EditorView({state, parent: editorHostRef.current});
    viewRef.current = view;

    return () => view.destroy();
  }, [initialCode]);

  const run = async () => {
    const view = viewRef.current;
    if (!view) return;

    setStatus('running');
    setOutput(null);

    try {
      const response = await fetch(PISTON_URL, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
          language: 'java',
          version: '*',
          files: [{name: filename, content: view.state.doc.toString()}],
        }),
      });

      if (!response.ok) {
        throw new Error(`execution service responded with HTTP ${response.status}`);
      }

      const result = await response.json();
      const compileErrors = result.compile?.stderr ? `${result.compile.stderr}\n` : '';
      const runOutput = `${result.run?.stdout ?? ''}${result.run?.stderr ?? ''}`;
      const combined = `${compileErrors}${runOutput}`.trim();

      setOutput(combined.length > 0 ? combined : '(no output)');
      setStatus('done');
    } catch (err) {
      setOutput(`Could not reach the execution service: ${(err as Error).message}`);
      setStatus('error');
    }
  };

  return (
    <div className={styles.playground}>
      <div className={styles.playgroundHead}>
        <span className={styles.dot} />
        <span className={styles.dot} />
        <span className={styles.dot} />
        <span className={styles.filename}>{filename}</span>
        <button
          type="button"
          className={styles.runButton}
          onClick={run}
          disabled={status === 'running'}
        >
          {status === 'running' ? 'running…' : 'run ▸'}
        </button>
      </div>

      <div ref={editorHostRef} className={styles.editorHost} />

      {output !== null && (
        <pre className={status === 'error' ? styles.outputError : styles.output}>{output}</pre>
      )}

      <p className={styles.disclaimer}>
        Runs on a public, sandboxed Java runtime (Piston) — not the resiliencia library itself.
        resiliencia isn&apos;t published to Maven Central yet, so this is a small, self-contained sketch of
        the retry loop rather than a real import.
        {/* TODO: replace this sketch with a real `resiliencia-patterns` import once the artifacts are published. */}
      </p>
    </div>
  );
}

export default function JavaPlayground({
  initialCode,
  filename = 'Playground.java',
}: {
  initialCode: string;
  filename?: string;
}): React.ReactNode {
  return (
    <BrowserOnly fallback={<div className={styles.loadingPlaceholder}>Loading playground…</div>}>
      {() => <PlaygroundEditor initialCode={initialCode} filename={filename} />}
    </BrowserOnly>
  );
}
