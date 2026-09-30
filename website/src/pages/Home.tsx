import { Hero } from '../sections/Hero';
import { Trust } from '../sections/Trust';
import { Pipeline } from '../sections/Pipeline';
import { Features } from '../sections/Features';
import { Why } from '../sections/Why';
import { How } from '../sections/How';
import { Roadmap } from '../sections/Roadmap';
import { Tech } from '../sections/Tech';

export function Home() {
  return (
    <>
      <Hero />
      <Trust />
      <Pipeline />
      <Features />
      <Why />
      <How />
      <Roadmap />
      <Tech />
    </>
  );
}
