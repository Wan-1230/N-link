import { Changelog } from '../sections/Changelog';
import { LatestRelease } from '../sections/LatestRelease';
import { Support } from '../sections/Support';

export function Releases() {
  return (
    <>
      <LatestRelease />
      <Changelog compact />
      <Support />
    </>
  );
}
