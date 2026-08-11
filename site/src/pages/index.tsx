import type {ReactNode} from 'react';
import Head from '@docusaurus/Head';
import Link from '@docusaurus/Link';
import useBaseUrl from '@docusaurus/useBaseUrl';
import useBrokenLinks from '@docusaurus/useBrokenLinks';
import SiteHeaderNav from '@site/src/components/SiteHeaderNav';

import styles from './index.module.css';

function PatternIcon({name}: {name: 'retry' | 'timeout' | 'circuit-breaker' | 'bulkhead' | 'rate-limiter'}) {
  switch (name) {
    case 'retry':
      return (
        <svg className={styles.patternIcon} viewBox="0 0 32 32" aria-hidden="true">
          <circle cx="16" cy="16" r="11" fill="none" stroke="currentColor" strokeWidth="2" strokeDasharray="4 5" strokeLinecap="round" />
          <polygon points="24,7 29,9 25,13" fill="currentColor" />
        </svg>
      );
    case 'timeout':
      return (
        <svg className={styles.patternIcon} viewBox="0 0 32 32" aria-hidden="true">
          <circle cx="16" cy="16" r="11" fill="none" stroke="currentColor" strokeWidth="2" />
          <line x1="16" y1="16" x2="16" y2="9" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          <line x1="16" y1="16" x2="21" y2="18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
        </svg>
      );
    case 'circuit-breaker':
      return (
        <svg className={styles.patternIcon} viewBox="0 0 32 32" aria-hidden="true">
          <circle cx="6" cy="16" r="2" fill="currentColor" />
          <circle cx="26" cy="16" r="2" fill="currentColor" />
          <line x1="8" y1="16" x2="14" y2="16" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          <line x1="14" y1="16" x2="24" y2="9" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          <line x1="20" y1="16" x2="24" y2="16" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
        </svg>
      );
    case 'bulkhead':
      return (
        <svg className={styles.patternIcon} viewBox="0 0 32 32" aria-hidden="true">
          <rect x="6" y="7" width="20" height="18" fill="none" stroke="currentColor" strokeWidth="2" />
          <line x1="13" y1="7" x2="13" y2="25" stroke="currentColor" strokeWidth="2" />
          <line x1="19" y1="7" x2="19" y2="25" stroke="currentColor" strokeWidth="2" />
        </svg>
      );
    case 'rate-limiter':
      return (
        <svg className={styles.patternIcon} viewBox="0 0 32 32" aria-hidden="true">
          <path d="M6 22 A 11 11 0 0 1 26 22" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          <line x1="16" y1="22" x2="21" y2="14" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          <circle cx="16" cy="22" r="1.6" fill="currentColor" />
        </svg>
      );
  }
}

function SiteHeader() {
  return (
    <header className={styles.siteHeader}>
      <SiteHeaderNav />
    </header>
  );
}

function Hero() {
  const bannerSrc = useBaseUrl('/img/hero-banner.png');
  return (
    <section className={styles.hero}>
      <div className={styles.heroBanner}>
        <img src={bannerSrc} alt="Resiliencia" width={1880} height={837} />
      </div>
      <h1 className="visually-hidden">Resiliencia — resilience patterns for Java 21</h1>

      <div className={styles.heroBody}>
        <div className={styles.dimensionLine}>
          <p className={styles.dimensionCaption}>five patterns · one fluent api · zero core dependencies</p>
        </div>

        <p className={styles.heroLede}>
          Retry, Timeout, CircuitBreaker, Bulkhead and RateLimiter for Java 21+. Built on virtual
          threads and composed with an explicit order the library validates for you — no reflection,
          no magic, nothing to configure twice.
        </p>

        <div className={styles.heroActions}>
          <a className={styles.btnStamp} href="#guides">read the guides →</a>
          <a className="btn-outline" href="#javadoc">browse the javadoc</a>
        </div>
      </div>

      <ul className={styles.specStrip}>
        <li>jdk 21+</li>
        <li>0 deps · core / patterns / compose</li>
        <li>5 patterns</li>
        <li>virtual threads</li>
      </ul>
    </section>
  );
}

function Patterns() {
  useBrokenLinks().collectAnchor('patterns');
  return (
    <section className={`${styles.section} ${styles.patterns}`} id="patterns">
      <div className={styles.sectionInner}>
        <p className="eyebrow">patterns</p>
        <h2>Five ways to fail on purpose</h2>
        <p className="section-lede">
          Each one implements <code>Resilient</code> — <code>call()</code>, <code>callAsync()</code>, or{' '}
          <code>outcome()</code> if you&apos;d rather not deal with exceptions at all.
        </p>

        <div className={styles.patternsGrid}>
          <Link className={styles.patternCard} to="/docs/patterns/retry">
            <PatternIcon name="retry" />
            <h3>Retry</h3>
            <p>Retries a failing call with a configurable number of attempts, wait duration, and optional exponential backoff.</p>
          </Link>

          <Link className={styles.patternCard} to="/docs/patterns/timeout">
            <PatternIcon name="timeout" />
            <h3>Timeout</h3>
            <p>Cancels a call that runs too long. Real cancellation via virtual&nbsp;thread interruption — not polling.</p>
          </Link>

          <Link className={styles.patternCard} to="/docs/patterns/circuit-breaker">
            <PatternIcon name="circuit-breaker" />
            <h3>CircuitBreaker</h3>
            <p>Stops calling a degraded dependency. Closed, Open, HalfOpen — modeled as a sealed <code>CircuitState</code>.</p>
          </Link>

          <Link className={styles.patternCard} to="/docs/patterns/bulkhead">
            <PatternIcon name="bulkhead" />
            <h3>Bulkhead</h3>
            <p>Caps concurrent calls with a semaphore. Blocking a virtual thread while it waits is cheap.</p>
          </Link>

          <Link className={styles.patternCard} to="/docs/patterns/rate-limiter">
            <PatternIcon name="rate-limiter" />
            <h3>RateLimiter</h3>
            <p>Caps how often a call may run inside a configurable time window.</p>
          </Link>
        </div>
      </div>
    </section>
  );
}

function Composition() {
  return (
    <section className={styles.section} id="composition">
      <div className={styles.sectionInner}>
        <div className={styles.compositionInner}>
          <div>
            <p className="eyebrow">composition</p>
            <h2>Chain them with Policy</h2>
            <p className="section-lede" style={{marginBottom: 0}}>
              <code>Policy.with(...).then(...)</code> builds an explicit call chain. The order is checked at
              construction time — not every combination makes sense.
            </p>
          </div>
          <div className={styles.codePanel}>
            <div className={styles.codePanelHead}>
              <span className={styles.dot} />
              <span className={styles.dot} />
              <span className={styles.dot} />
              <span className={styles.codeFilename}>Checkout.java</span>
            </div>
            <pre className={styles.codeBlock}><code>{`var policy = Policy.with(circuitBreaker)
                    .then(retry)
                    .then(timeout);

policy.call(() -> api.fetchOrder(id));`}</code></pre>
            <div className={styles.annotation}>
              <span className={styles.annotationMark} aria-hidden="true" />
              <p>
                Retry wrapping a CircuitBreaker throws <code>InvalidPolicyException</code> at construction —
                there&apos;s no legitimate use case for retrying past an open circuit.
              </p>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

function Guides() {
  useBrokenLinks().collectAnchor('guides');
  return (
    <section className={styles.section} id="guides">
      <div className={styles.sectionInner}>
        <p className="eyebrow">guides</p>
        <div className={styles.guidesHeadingRow}>
          <h2>Written for the way you&apos;ll actually use it</h2>
          <span className={styles.stampDraft}>draft — yours to write</span>
        </div>
        <p className="section-lede">
          Short, practical guides, not a full API reference. These three are placeholders for the shape of the
          section.
        </p>

        <div className={styles.guidesGrid}>
          <Link className={styles.guideCard} to="/docs/intro">
            <p className={styles.guideIndex}>01</p>
            <h3>Getting started</h3>
            <p>Add resiliencia-core and resiliencia-patterns, then wrap your first call in a Retry.</p>
          </Link>
          <article className={styles.guideCard}>
            <p className={styles.guideIndex}>02</p>
            <h3>Composing a Policy</h3>
            <p>Chain CircuitBreaker, Retry and Timeout with an order the library validates for you.</p>
          </article>
          <article className={styles.guideCard}>
            <p className={styles.guideIndex}>03</p>
            <h3>Testing with ManualClock</h3>
            <p>Drive retries and timeouts deterministically with resiliencia-test — no <code>Thread.sleep</code> in your test suite.</p>
          </article>
        </div>
      </div>
    </section>
  );
}

function JavadocSection() {
  const useApidocsUrl = (path: string) => useBaseUrl(`/apidocs/${path}`);
  useBrokenLinks().collectAnchor('javadoc');
  return (
    <section className={`${styles.section} ${styles.javadoc}`} id="javadoc">
      <div className={styles.sectionInner}>
        <p className="eyebrow">javadoc</p>
        <h2>One module at a time</h2>
        <p className="section-lede">Each module ships its own Javadoc jar — no monolithic API surface to wade through.</p>

        <div className="table-scroll">
          <table className="bom-table">
            <thead>
              <tr><th>module</th><th>what&apos;s in it</th><th>external deps</th></tr>
            </thead>
            <tbody>
              <tr>
                <td><a href={useApidocsUrl('io.github.teceli.resiliencia.core/module-summary.html')}>resiliencia-core</a></td>
                <td>Resilient, Outcome&lt;T&gt;, exception hierarchy, SPI</td>
                <td>none</td>
              </tr>
              <tr>
                <td><a href={useApidocsUrl('io.github.teceli.resiliencia.patterns/module-summary.html')}>resiliencia-patterns</a></td>
                <td>Retry, Timeout, CircuitBreaker, Bulkhead, RateLimiter</td>
                <td>none</td>
              </tr>
              <tr>
                <td><a href={useApidocsUrl('io.github.teceli.resiliencia.compose/module-summary.html')}>resiliencia-compose</a></td>
                <td>Policy — fluent, validated composition</td>
                <td>none</td>
              </tr>
              <tr>
                <td><a href={useApidocsUrl('io.github.teceli.resiliencia.metrics/module-summary.html')}>resiliencia-metrics</a></td>
                <td>ResilienciaMetrics abstraction</td>
                <td>none</td>
              </tr>
              <tr>
                <td><a href={useApidocsUrl('io.github.teceli.resiliencia.micrometer/module-summary.html')}>resiliencia-micrometer</a></td>
                <td>ResilienciaMetrics over Micrometer&apos;s MeterRegistry</td>
                <td>Micrometer</td>
              </tr>
              <tr>
                <td><a href={useApidocsUrl('io.github.teceli.resiliencia.test/module-summary.html')}>resiliencia-test</a></td>
                <td>Fakes, ManualClock, JUnit 5 assertions</td>
                <td>JUnit 5</td>
              </tr>
              <tr>
                <td>resiliencia-spring <span className="module-status">(coming soon)</span></td>
                <td>Auto-config, @WithRetry, @WithCircuitBreaker</td>
                <td>Spring Boot</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p className="margin-note">
          ↳ published from each module&apos;s javadoc:jar to Maven Central. This site also hosts a combined,
          aggregated copy, rebuilt by CI on every push to main.
        </p>
        <a className={`btn-outline ${styles.javadocCta}`} href={useApidocsUrl('index.html')}>open the aggregated javadoc →</a>
      </div>
    </section>
  );
}

function SiteFooter() {
  const apidocsIndex = useBaseUrl('/apidocs/index.html');
  return (
    <footer className={styles.footer}>
      <div className={`${styles.sectionInner} ${styles.footerGrid}`}>
        <dl className={styles.titleBlock}>
          <dt>title</dt><dd>Resiliencia</dd>
          <dt>type</dt><dd>resilience patterns library</dd>
          <dt>jdk</dt><dd>21+</dd>
          <dt>license</dt><dd>apache-2.0</dd>
        </dl>
        <nav className={styles.footerNav} aria-label="Footer">
          <a href="https://github.com/tec-eli/resiliencia" target="_blank" rel="noopener">github</a>
          <a href="#guides">guides</a>
          <a href={apidocsIndex}>javadoc</a>
          <a href="https://github.com/tec-eli/resiliencia/issues" target="_blank" rel="noopener">issues</a>
        </nav>
      </div>
    </footer>
  );
}

export default function Home(): ReactNode {
  return (
    <>
      <Head>
        <title>Resiliencia — resilience patterns for Java 21</title>
      </Head>
      <a href="#main" className="skip-link">Skip to content</a>
      <SiteHeader />
      <main id="main">
        <Hero />
        <Patterns />
        <Composition />
        <Guides />
        <JavadocSection />
      </main>
      <SiteFooter />
    </>
  );
}
