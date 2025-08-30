import React, { useMemo, useRef, useState } from 'react';
import {
  View,
  Text,
  Image,
  Button,
  StyleSheet,
  ActivityIndicator,
  TouchableOpacity,
  Alert,
  Platform,
  Dimensions,
  Animated,
} from 'react-native';

const { width: SCREEN_W, height: SCREEN_H } = Dimensions.get('window');

export default function EditorScreen({ route, navigation }) {
  const { mediaPath } = route?.params ?? {};
  const [loading, setLoading] = useState(true);
  const [rotateDeg, setRotateDeg] = useState(0);

  // Basic zoom (double-tap to toggle 1x / 2x)
  const scale = useRef(new Animated.Value(1)).current;
  const lastTapRef = useRef(0);
  const [zoomed, setZoomed] = useState(false);

  const imageUri = useMemo(() => {
    if (!mediaPath) return undefined;
    // Support file paths returned by native & content URIs
    if (mediaPath.startsWith('content://')) return mediaPath;
    return mediaPath.startsWith('file://') ? mediaPath : `file://${mediaPath}`;
  }, [mediaPath]);

  const onImageLoadEnd = () => setLoading(false);

  const onDoubleTap = () => {
    const now = Date.now();
    if (now - lastTapRef.current < 250) {
      // Double tapped
      Animated.spring(scale, {
        toValue: zoomed ? 1 : 2,
        useNativeDriver: true,
      }).start(() => setZoomed(!zoomed));
    }
    lastTapRef.current = now;
  };

  // ---- Placeholder actions (wire up your real tooling later) ----
  const onRotate = () => {
    setRotateDeg((d) => (d + 90) % 360);
  };

  const onCrop = () => {
    Alert.alert('Crop (placeholder)', 'Hook this up to your crop flow.');
  };

  const onAnnotate = () => {
    Alert.alert('Annotate (placeholder)', 'Hook this up to draw/markup flow.');
  };

  const onAdjust = () => {
    Alert.alert('Adjust (placeholder)', 'Hook this to brightness/contrast, etc.');
  };

  const onSave = () => {
    Alert.alert('Saved (placeholder)', 'Return edited image path to caller.');
  };

  const onDiscard = () => {
    Alert.alert('Discard changes?', 'This will lose current edits.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Discard', style: 'destructive', onPress: () => navigation.goBack() },
    ]);
  };

  const onBack = () => navigation.goBack();

  return (
    <View style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.headerTitle}>Editor</Text>
        <View style={styles.headerRight}>
          <Button title="Back" onPress={onBack} />
        </View>
      </View>

      {/* Info row */}
      <View style={styles.infoRow}>
        <Text numberOfLines={1} style={styles.pathText}>
          {imageUri || '(no media)'}
        </Text>
      </View>

      {/* Canvas */}
      <View style={styles.canvas}>
        {imageUri ? (
          <>
            {loading && (
              <View style={styles.loading}>
                <ActivityIndicator />
                <Text style={{ marginTop: 8 }}>Loading image…</Text>
              </View>
            )}

            <Animated.View
              style={{
                transform: [{ scale }, { rotate: `${rotateDeg}deg` }],
              }}
            >
              <TouchableOpacity activeOpacity={1} onPress={onDoubleTap}>
                <Image
                  source={{ uri: imageUri }}
                  onLoadEnd={onImageLoadEnd}
                  resizeMode="contain"
                  style={styles.image}
                />
              </TouchableOpacity>
            </Animated.View>
          </>
        ) : (
          <View style={styles.empty}>
            <Text>No mediaPath provided.</Text>
          </View>
        )}
      </View>

      {/* Toolbar */}
      <View style={styles.toolbar}>
        <ToolButton label="Rotate" onPress={onRotate} />
        <ToolButton label="Crop" onPress={onCrop} />
        <ToolButton label="Annotate" onPress={onAnnotate} />
        <ToolButton label="Adjust" onPress={onAdjust} />
      </View>

      {/* Footer actions */}
      <View style={styles.footer}>
        <TouchableOpacity style={[styles.cta, styles.discard]} onPress={onDiscard}>
          <Text style={styles.ctaText}>Discard</Text>
        </TouchableOpacity>
        <TouchableOpacity style={[styles.cta, styles.save]} onPress={onSave}>
          <Text style={[styles.ctaText, styles.ctaTextDark]}>Save</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

function ToolButton({ label, onPress }) {
  return (
    <TouchableOpacity onPress={onPress} style={styles.toolBtn}>
      <Text style={styles.toolLabel}>{label}</Text>
    </TouchableOpacity>
  );
}

const PADDING = 16;

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0b0b0b' },

  header: {
    paddingTop: Platform.OS === 'android' ? 24 : 12,
    paddingHorizontal: PADDING,
    paddingBottom: 12,
    flexDirection: 'row',
    alignItems: 'center',
  },
  headerTitle: { flex: 1, color: 'white', fontSize: 18, fontWeight: '700' },
  headerRight: { marginLeft: 8 },

  infoRow: {
    paddingHorizontal: PADDING,
    paddingBottom: 8,
  },
  pathText: { color: '#aaa', fontSize: 12 },

  canvas: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    marginHorizontal: PADDING,
    backgroundColor: '#111',
    borderRadius: 12,
    overflow: 'hidden',
  },
  image: {
    width: SCREEN_W - PADDING * 2,
    height: SCREEN_H * 0.55,
  },
  loading: { position: 'absolute', alignItems: 'center', justifyContent: 'center' },
  empty: { alignItems: 'center', justifyContent: 'center' },

  toolbar: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    paddingHorizontal: PADDING,
    paddingVertical: 12,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#222',
    backgroundColor: '#0f0f0f',
  },
  toolBtn: {
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 8,
    backgroundColor: '#1b1b1b',
  },
  toolLabel: { color: 'white', fontSize: 14, fontWeight: '600' },

  footer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingHorizontal: PADDING,
    paddingTop: 8,
    paddingBottom: PADDING,
    backgroundColor: '#0b0b0b',
  },
  cta: {
    flex: 1,
    marginHorizontal: 6,
    alignItems: 'center',
    paddingVertical: 14,
    borderRadius: 10,
  },
  discard: { backgroundColor: '#2a2a2a' },
  save: { backgroundColor: '#f2f2f2' },
  ctaText: { color: 'white', fontSize: 16, fontWeight: '700' },
  ctaTextDark: { color: '#0b0b0b' },
});