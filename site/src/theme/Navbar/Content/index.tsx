import type {ReactNode} from 'react';
import {useNavbarMobileSidebar} from '@docusaurus/theme-common/internal';
import NavbarMobileSidebarToggle from '@theme/Navbar/MobileSidebar/Toggle';
import SiteHeaderNav from '@site/src/components/SiteHeaderNav';

export default function NavbarContent(): ReactNode {
  const mobileSidebar = useNavbarMobileSidebar();
  return (
    <>
      {!mobileSidebar.disabled && <NavbarMobileSidebarToggle />}
      <SiteHeaderNav />
    </>
  );
}
