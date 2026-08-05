import type {ReactNode} from 'react';
import Link from '@docusaurus/Link';

import styles from './styles.module.css';

export default function SiteHeaderNav(): ReactNode {
  return (
    <div className={styles.headerInner}>
      <Link to="/" className={styles.wordmarkSmall}>Resiliencia</Link>
      <nav className={styles.nav} aria-label="Primary">
        <Link to="/#patterns">patterns</Link>
        <Link to="/#guides">guides</Link>
        <Link to="/#javadoc">javadoc</Link>
        <a href="https://github.com/tec-eli/resiliencia" className={styles.navExternal} target="_blank" rel="noopener">
          github ↗
        </a>
      </nav>
    </div>
  );
}
