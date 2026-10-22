import type { TalentDraft } from './types.ts';
export function validateTalent(draft: TalentDraft): string {
  if (draft.display_name.trim().length < 2 || draft.display_name.trim().length > 100)
    return 'Enter a talent name of 2–100 characters.';
  if (draft.city.trim().length < 2 || draft.city.trim().length > 100)
    return 'Enter a city of 2–100 characters.';
  if (!['actors', 'models', 'creators', 'musicians', 'photographers'].includes(draft.category_id))
    return 'Choose a category.';
  if (!Number.isInteger(draft.day_rate) || draft.day_rate < 0 || draft.day_rate > 10000000)
    return 'Enter a whole daily rate between ₹0 and ₹10,000,000.';
  if (draft.bio.length > 2000) return 'Keep your biography under 2,001 characters.';
  if (draft.skills.length > 20 || draft.skills.some((skill) => skill.length > 80))
    return 'Use up to 20 skills, each no longer than 80 characters.';
  return '';
}
/** Whitelist writable fields even when JavaScript callers pass a full database row. */
export function talentPayload(value: TalentDraft): TalentDraft {
  return {
    display_name: value.display_name,
    category_id: value.category_id,
    city: value.city,
    day_rate: value.day_rate,
    bio: value.bio,
    skills: value.skills,
    available: value.available,
    published: value.published,
    portrait_path: value.portrait_path,
  };
}
