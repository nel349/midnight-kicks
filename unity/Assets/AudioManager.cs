using UnityEngine;

/// <summary>
/// Midnight Kicks audio — La Bombonera atmosphere for the penalty shootout.
///
/// Loads from Resources/Audio/ (no extension in code — Unity strips it). Files
/// the build ships today:
///   Audio/Music/Bombonera_Beat_drum_chant.mp3  — looping drum-and-chant bed
///   Audio/Music/Intro-theme.mp3                — optional match-open sting
///   Audio/SFX/penalty_kick_sound_1.wav         — boot striking the ball
///   Audio/SFX/penalty_hitting_the_goal_web.wav — ball ripples the net (goal)
///   Audio/SFX/bombonera_drum_double_strike.wav — the stand's defiant drum hit
///   Audio/Crowd/roaring_crowd_1..3.wav         — crowd eruptions (random pick)
///
/// **The mix (why it's layered this way):** La Bombonera is carried by its
/// drums and chant, not a generic murmur — so the *bed* is the looping drum
/// chant, and the one-shots are reactions on top of it:
///   - KICK  → the boot thump (sharp, full).
///   - GOAL  → net ripple + a full-throated crowd ROAR (random clip + slight
///             random pitch so repeated goals never sound identical).
///   - SAVE  → the defiant drum double-strike + a *lower* roar — the keeper's
///             end erupts, but it reads distinctly from a goal (quieter, lower
///             pitch), so the ear can tell goal from save without watching.
///
/// Self-attaches at scene load; every clip is optional (missing → a warning +
/// that cue silently no-ops, never a crash), so the build runs before every
/// asset lands. Unity C# change — takes effect on the next Unity re-export.
/// </summary>
public class AudioManager : MonoBehaviour
{
    // ── Resource paths (real files, extension-less) ──
    private const string MusicBed = "Audio/Music/Bombonera_Beat_drum_chant";
    private const string MusicIntro = "Audio/Music/Intro-theme";
    private const string SfxKick = "Audio/SFX/penalty_kick_sound_1";
    private const string SfxNet = "Audio/SFX/penalty_hitting_the_goal_web";
    private const string SfxDrumStrike = "Audio/SFX/bombonera_drum_double_strike";
    private static readonly string[] CrowdRoars =
    {
        "Audio/Crowd/roaring_crowd_1",
        "Audio/Crowd/roaring_crowd_2",
        "Audio/Crowd/roaring_crowd_3",
    };

    // ── Mix levels (0..1) ──
    private const float BedVolume = 0.30f;       // drum bed under everything
    private const float KickVolume = 1.0f;       // sharp, full
    private const float NetVolume = 0.9f;        // ball into the net
    private const float DrumStrikeVolume = 0.7f; // accent
    private const float RoarGoalVolume = 0.9f;   // the eruption
    private const float RoarSaveVolume = 0.5f;   // keeper's end — quieter than a goal

    private static AudioManager _instance;

    // Separate sources so a roar never cuts the boot thump (and vice versa), and
    // the looping bed is independent of both.
    private AudioSource _bed;
    private AudioSource _sfx;
    private AudioSource _crowd;

    private AudioClip _bedClip;
    private AudioClip _introClip;
    private AudioClip _kick;
    private AudioClip _net;
    private AudioClip _drumStrike;
    private AudioClip[] _roars;

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    private static void AutoCreate()
    {
        if (_instance != null) return;
        var go = new GameObject("AudioManager");
        _instance = go.AddComponent<AudioManager>();
        DontDestroyOnLoad(go);
        Debug.Log("[AudioManager] Auto-created");
    }

    void Awake()
    {
        if (_instance != null && _instance != this)
        {
            Destroy(gameObject);
            return;
        }
        _instance = this;

        _bed = gameObject.AddComponent<AudioSource>();
        _bed.playOnAwake = false;
        _bed.loop = true;

        _sfx = gameObject.AddComponent<AudioSource>();
        _sfx.playOnAwake = false;

        _crowd = gameObject.AddComponent<AudioSource>();
        _crowd.playOnAwake = false;

        LoadClips();
        StartAmbience();
    }

    private void LoadClips()
    {
        _bedClip = LoadClip(MusicBed);
        _introClip = LoadClip(MusicIntro);
        _kick = LoadClip(SfxKick);
        _net = LoadClip(SfxNet);
        _drumStrike = LoadClip(SfxDrumStrike);

        _roars = new AudioClip[CrowdRoars.Length];
        for (int i = 0; i < CrowdRoars.Length; i++) _roars[i] = LoadClip(CrowdRoars[i]);
    }

    private static AudioClip LoadClip(string resourcePath)
    {
        var clip = Resources.Load<AudioClip>(resourcePath);
        if (clip == null)
            Debug.LogWarning($"[AudioManager] Missing clip at Resources/{resourcePath} — add a .wav or .mp3 there");
        return clip;
    }

    // ── Public cue API (static, null-safe) ──

    /// <summary>Boot striking the ball.</summary>
    public static void PlayKick() => _instance?.PlayOneShot(_instance._sfx, _instance._kick, KickVolume);

    /// <summary>Goal — net ripple + a full crowd eruption.</summary>
    public static void PlayGoal()
    {
        var m = _instance;
        if (m == null) return;
        m.PlayOneShot(m._sfx, m._net, NetVolume);
        m.PlayRoar(RoarGoalVolume, basePitch: 1.0f);
    }

    /// <summary>Save — the defiant drum double-strike + a lower roar from the keeper's end.</summary>
    public static void PlaySave()
    {
        var m = _instance;
        if (m == null) return;
        m.PlayOneShot(m._sfx, m._drumStrike, DrumStrikeVolume);
        m.PlayRoar(RoarSaveVolume, basePitch: 0.94f);
    }

    /// <summary>Route a round's "goal"/"save" result to the right reaction.</summary>
    public static void PlayRoundResult(string result)
    {
        if (result == "goal") PlayGoal();
        else if (result == "save") PlaySave();
    }

    /// <summary>
    /// Match over — a final eruption as the result screen appears. Full roar +
    /// drums for a win; a restrained, lower roar otherwise so victory and defeat
    /// sound different.
    /// </summary>
    public static void PlayMatchEnd(bool localWon)
    {
        var m = _instance;
        if (m == null) return;
        m.PlayRoar(localWon ? 1.0f : 0.4f, basePitch: localWon ? 1.03f : 0.9f);
        if (localWon) m.PlayOneShot(m._sfx, m._drumStrike, DrumStrikeVolume);
    }

    /// <summary>Optional match-open sting (the intro theme), played once over the bed.</summary>
    public static void PlayIntroSting()
    {
        var m = _instance;
        if (m == null || m._introClip == null) return;
        m._sfx.PlayOneShot(m._introClip, 0.6f);
    }

    public static void StopAmbience() => _instance?._bed.Stop();

    // ── Internals ──

    private void StartAmbience()
    {
        if (_bedClip == null) return;
        _bed.clip = _bedClip;
        _bed.volume = BedVolume;
        if (!_bed.isPlaying) _bed.Play();
    }

    /// <summary>
    /// Pick a random roar and play it with a small random pitch wobble so back-
    /// to-back goals (or saves) never sound like the same canned clip.
    /// </summary>
    private void PlayRoar(float volume, float basePitch)
    {
        if (_roars == null || _roars.Length == 0) return;
        var clip = _roars[Random.Range(0, _roars.Length)];
        if (clip == null) return;
        _crowd.pitch = basePitch + Random.Range(-0.05f, 0.05f);
        _crowd.PlayOneShot(clip, volume);
    }

    private void PlayOneShot(AudioSource source, AudioClip clip, float volume)
    {
        if (clip == null) return;
        source.PlayOneShot(clip, volume);
    }
}
