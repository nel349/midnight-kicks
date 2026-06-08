using UnityEditor;
using UnityEngine;

/// <summary>
/// Applies Android-friendly import presets when audio is dropped into Resources/Audio/.
/// </summary>
public class AudioImportPostprocessor : AssetPostprocessor
{
    private const string AudioRoot = "/Resources/Audio/";

    void OnPreprocessAudio()
    {
        if (!assetPath.Contains(AudioRoot)) return;

        var importer = (AudioImporter)assetImporter;
        importer.forceToMono = true;

        if (assetPath.Contains("/Audio/SFX/"))
            ApplySfx(importer);
        else if (assetPath.Contains("/Audio/Crowd/"))
            ApplyCrowd(importer);
        else if (assetPath.Contains("/Audio/Music/"))
            ApplyMusic(importer);
    }

    private static void ApplySfx(AudioImporter importer)
    {
        var settings = importer.defaultSampleSettings;
        settings.loadType = AudioClipLoadType.DecompressOnLoad;
        settings.compressionFormat = AudioCompressionFormat.ADPCM;
        settings.quality = 1f;
        importer.defaultSampleSettings = settings;

        var android = importer.GetOverrideSampleSettings("Android");
        android.loadType = AudioClipLoadType.DecompressOnLoad;
        android.compressionFormat = AudioCompressionFormat.ADPCM;
        android.quality = 1f;
        importer.SetOverrideSampleSettings("Android", android);
    }

    private static void ApplyCrowd(AudioImporter importer)
    {
        var settings = importer.defaultSampleSettings;
        settings.loadType = AudioClipLoadType.CompressedInMemory;
        settings.compressionFormat = AudioCompressionFormat.Vorbis;
        settings.quality = 0.7f;
        importer.defaultSampleSettings = settings;

        var android = importer.GetOverrideSampleSettings("Android");
        android.loadType = AudioClipLoadType.CompressedInMemory;
        android.compressionFormat = AudioCompressionFormat.Vorbis;
        android.quality = 0.7f;
        importer.SetOverrideSampleSettings("Android", android);
    }

    private static void ApplyMusic(AudioImporter importer)
    {
        importer.forceToMono = false;

        var settings = importer.defaultSampleSettings;
        settings.loadType = AudioClipLoadType.Streaming;
        settings.compressionFormat = AudioCompressionFormat.Vorbis;
        settings.quality = 0.6f;
        importer.defaultSampleSettings = settings;

        var android = importer.GetOverrideSampleSettings("Android");
        android.loadType = AudioClipLoadType.Streaming;
        android.compressionFormat = AudioCompressionFormat.Vorbis;
        android.quality = 0.6f;
        importer.SetOverrideSampleSettings("Android", android);
    }
}