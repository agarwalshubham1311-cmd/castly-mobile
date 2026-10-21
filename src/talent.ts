export const categories = ['All talent', 'Actors', 'Models', 'Creators', 'Musicians'] as const;
export type Category = typeof categories[number];
export type Talent = { id: string; name: string; category: Category; city: string; rate: number; rating: string; initials: string; color: string; available: boolean; bio: string; skills: string[] };
export const talents: Talent[] = [
  { id: 'aria', name: 'Aria Kapoor', category: 'Actors', city: 'Mumbai', rate: 18000, rating: '4.9', initials: 'AK', color: '#8b649e', available: true, bio: 'An expressive performer bringing warmth and authenticity to every frame. Experienced in independent cinema, brand films and editorial storytelling.', skills: ['Acting', 'Brand films', 'Hindi', 'English'] },
  { id: 'rehan', name: 'Rehan Mehta', category: 'Models', city: 'Delhi', rate: 15000, rating: '4.8', initials: 'RM', color: '#536c7a', available: true, bio: 'A versatile fashion and commercial model with an eye for movement. Creating memorable imagery for emerging labels and established brands.', skills: ['Editorial', 'Runway', 'Commercial'] },
  { id: 'maya', name: 'Maya Sen', category: 'Creators', city: 'Bengaluru', rate: 12000, rating: '4.9', initials: 'MS', color: '#9b7166', available: false, bio: 'A visual storyteller exploring style, travel and everyday inspiration. Thoughtful content with a distinctive creative voice.', skills: ['Lifestyle', 'Video', 'Photography'] },
  { id: 'ishaan', name: 'Ishaan Roy', category: 'Musicians', city: 'Mumbai', rate: 22000, rating: '5.0', initials: 'IR', color: '#65775d', available: true, bio: 'Singer, songwriter and live performer blending soulful melodies with contemporary production. Available for creative collaborations and live sessions.', skills: ['Vocals', 'Guitar', 'Live performance'] },
];
export type Filters = { city: string; maxRate: number; availableOnly: boolean };
export const defaultFilters: Filters = { city: 'All cities', maxRate: 30000, availableOnly: false };
export function findTalents(query: string, category: Category, filters: Filters): Talent[] {
  const term = query.trim().toLowerCase();
  return talents.filter(t => (category === 'All talent' || t.category === category) && (!term || [t.name, t.city, t.category, ...t.skills].join(' ').toLowerCase().includes(term)) && (filters.city === 'All cities' || t.city === filters.city) && t.rate <= filters.maxRate && (!filters.availableOnly || t.available));
}
export const money = (value: number) => `₹${value.toLocaleString('en-IN')}`;
