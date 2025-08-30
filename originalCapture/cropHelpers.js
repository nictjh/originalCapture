import { Image } from 'react-native';
import RNFS from 'react-native-fs';
import ImagePicker from 'react-native-image-crop-picker';

const ensureJpegExt = (p) => (/\.(jpe?g)$/i.test(p) ? p : `${p}.jpg`);

async function statOrNull(path) {
  try { return await RNFS.stat(path); } catch { return null; }
}

/** Put a copy in app-specific *external files* (more stable than external cache for uCrop) */
export async function prepareInputForCropperExternalFiles(srcPathOrUri) {
  // Normalize the source path
  const srcPath = srcPathOrUri.startsWith('file://') ? srcPathOrUri.slice(7) : srcPathOrUri;

  // Verify source file exists first
  const srcStat = await statOrNull(srcPath);
  if (!srcStat || !srcStat.isFile() || Number(srcStat.size) <= 0) {
    throw new Error(`Source file invalid or missing at ${srcPath} size=${srcStat?.size || 0}`);
  }

  console.log('[CROP] Source file verified:', srcPath, 'size:', srcStat.size);

  const dir = RNFS.ExternalDirectoryPath || RNFS.ExternalCachesDirectoryPath || RNFS.CachesDirectoryPath;
  const dest = ensureJpegExt(`${dir}/crop-src-${Date.now()}.jpg`);

  // Ensure directory exists
  await RNFS.mkdir(dir).catch(() => {}); // ignore if already exists

  // Copy with proper file:// URI for both source and destination
  const srcUri = srcPath.startsWith('file://') ? srcPath : `file://${srcPath}`;
  await RNFS.copyFile(srcUri, dest);

  // Verify the copy
  const destStat = await statOrNull(dest);
  if (!destStat || !destStat.isFile() || Number(destStat.size) <= 0) {
    throw new Error(`Copy failed: invalid file at ${dest} size=${destStat?.size || 0}`);
  }

  console.log('[CROP] File copied successfully to:', dest, 'size:', destStat.size);

  // Sanity decode via RN bridge (forces image decode)
  try {
    await new Promise((resolve, reject) => {
      Image.getSize(`file://${dest}`,
        (w, h) => {
          console.log('[CROP] Image decode successful, dimensions:', w, 'x', h);
          resolve();
        },
        (e) => reject(new Error('Decode check failed: ' + e?.message))
      );
    });
  } catch (decodeError) {
    // Clean up the failed copy
    try { await RNFS.unlink(dest); } catch {}
    throw decodeError;
  }

  return dest;
}

/** Open cropper → copy result back over original internal file */
export async function cropAndOverwriteOriginalExternalFiles(originalPathOrUri, { width = 1080, height = 1080 } = {}) {
  const originalPath = originalPathOrUri.startsWith('file://')
    ? originalPathOrUri.slice(7)
    : originalPathOrUri;

  console.log('[CROP] Starting crop process for:', originalPath);

  try {
    const input = await prepareInputForCropperExternalFiles(originalPathOrUri);
    console.log('[CROP] input', input, (await statOrNull(input))?.size);

    const result = await ImagePicker.openCropper({
      path: `file://${input}`, // ImagePicker needs file:// prefix for external storage
      width,
      height,
      cropping: true,
      compressImageQuality: 0.9,
      cropperToolbarTitle: 'Crop',
      includeExif: true, // Include EXIF data in result
    });

    const out = result.path;
    const outSt = await statOrNull(out);
    if (!outSt || !outSt.isFile() || Number(outSt.size) <= 0) {
      throw new Error(`Crop output invalid at ${out} size=${outSt?.size || 0}`);
    }

    // Log detailed crop information
    console.log('[CROP] === CROP DETAILS ===');
    console.log('[CROP] Original dimensions:', result.sourceWidth || 'unknown', 'x', result.sourceHeight || 'unknown');
    console.log('[CROP] Cropped dimensions:', result.width, 'x', result.height);
    console.log('[CROP] Crop area - X:', result.cropRect?.x || 'unknown', 'Y:', result.cropRect?.y || 'unknown');
    console.log('[CROP] Crop area - Width:', result.cropRect?.width || 'unknown', 'Height:', result.cropRect?.height || 'unknown');

    // Calculate zoom factor and position percentages
    if (result.sourceWidth && result.sourceHeight && result.cropRect) {
      const zoomX = result.sourceWidth / result.cropRect.width;
      const zoomY = result.sourceHeight / result.cropRect.height;
      const avgZoom = (zoomX + zoomY) / 2;

      const posXPercent = ((result.cropRect.x / result.sourceWidth) * 100).toFixed(1);
      const posYPercent = ((result.cropRect.y / result.sourceHeight) * 100).toFixed(1);
      const cropWidthPercent = ((result.cropRect.width / result.sourceWidth) * 100).toFixed(1);
      const cropHeightPercent = ((result.cropRect.height / result.sourceHeight) * 100).toFixed(1);

      console.log('[CROP] Zoom factor: ~' + avgZoom.toFixed(2) + 'x');
      console.log('[CROP] Crop position: ' + posXPercent + '% from left, ' + posYPercent + '% from top');
      console.log('[CROP] Crop size: ' + cropWidthPercent + '% width, ' + cropHeightPercent + '% height');
      console.log('[CROP] User cropped out ' + (100 - parseFloat(cropWidthPercent)).toFixed(1) + '% width, ' + (100 - parseFloat(cropHeightPercent)).toFixed(1) + '% height');
    }

    console.log('[CROP] File size: ' + (outSt.size / 1024).toFixed(1) + 'KB');
    console.log('[CROP] Quality: ' + (result.compressImageQuality || 0.9));
    console.log('[CROP] MIME:', result.mime || 'unknown');
    console.log('[CROP] === END CROP DETAILS ===');
    console.log('[CROP] output', out, outSt.size);

    // Copy result back to original location
    await RNFS.copyFile(out, originalPath);

    // Verify the final copy
    const finalStat = await statOrNull(originalPath);
    console.log('[CROP] Final file:', originalPath, 'size:', finalStat?.size || 0);

    // cleanup best-effort
    try { await RNFS.unlink(out); } catch {}
    try { await RNFS.unlink(input); } catch {}

    return originalPath;
  } catch (error) {
    console.log('[CROP][ERROR] Full error details:', error);
    throw error;
  }
}