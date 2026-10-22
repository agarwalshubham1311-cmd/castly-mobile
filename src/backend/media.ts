export function portfolioImageType(bytes: Uint8Array): { contentType: string; extension: string } {
  if (bytes.length > 5242880) throw new Error('Choose an image smaller than 5MB.');
  if (bytes.length >= 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255)
    return { contentType: 'image/jpeg', extension: 'jpg' };
  if (
    bytes.length >= 8 &&
    [137, 80, 78, 71, 13, 10, 26, 10].every((byte, index) => bytes[index] === byte)
  )
    return { contentType: 'image/png', extension: 'png' };
  if (
    bytes.length >= 12 &&
    String.fromCharCode(...bytes.slice(0, 4)) === 'RIFF' &&
    String.fromCharCode(...bytes.slice(8, 12)) === 'WEBP'
  )
    return { contentType: 'image/webp', extension: 'webp' };
  throw new Error('Choose a JPEG, PNG or WebP photograph. This file format is not supported.');
}
