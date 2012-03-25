package com.jbak.words;

import java.io.File;
import java.nio.CharBuffer;
import java.util.Collections;
import java.util.Comparator;
import java.util.Vector;

import android.os.Handler;

import com.jbak.JbakKeyboard.st;
import com.jbak.words.IWords.WordEntry;
import com.jbak.words.WordsIndex.IndexEntry;
/** Класс для поиска слов в словаре */
public class Words
{
    public String m_name;
    public int m_wordsLimit = 20;
    
    public static final String DEF_PATH              = "vocab/";
    public static final String DEF_EXT               = ".dic";
    public static final String INDEX_EXT               = ".index";
/** Сообщение, которое получает Handler. В этом сообщении объект - Vector&lt;WordEntry&gt;*/    
    public static final int MSG_GET_WORDS    = 0xfeded;
/** Словарный индекс */    
    WordsIndex m_index;
/** Файл словаря*/    
    LineFileReader m_file;
/** Обработчик, получающий сообщения о процессе обработки */    
    Handler m_handler;
    public boolean open(String name)
    {
        if(name.equals(m_name)&&canGiveWords())
            return true;
        String path = st.getSettingsPath()+DEF_PATH+name+DEF_EXT;
        m_name = name;
        String indexPath = path+INDEX_EXT;
        m_index = new WordsIndex();
        if(!new File(indexPath).exists())
        {
            if(!m_index.makeIndexFromVocab(path))
            {
                m_index = null;
                return false;
            }
            m_index.save(indexPath);
        }
        else
        {
            if(!m_index.load(indexPath))
                m_index = null;
        }
        try{
            m_file = new LineFileReader();
            m_file.open(path, "r");
        }
        catch (Throwable e) {
            m_file = null;
            return false;
        }
        return canGiveWords();
    }

    public void close()
    {
        m_index = null;
    }
/** Возвращает true, если возможно получить слова (словарь открыт на чтение и есть индекс) */
    public final boolean canGiveWords()
    {
        return m_index!=null;
    }
    String m_word;
    IndexEntry m_ie = new IndexEntry();
    /** Основная функция для получения набора слов из словаря 
    *@param word Часть слова для поиска 
    *@param handler Обработчик, в который прийдет набор слов в случае успешного выполнения. Тип сообщения - {@link #MSG_GET_WORDS}
     */
    public void getWordsSync(String word,Handler handler)
    {
        if(word==null||word.length()<2||!canGiveWords())
            return;
        m_handler = handler;
        m_word = word;
        m_ie.first = Character.toLowerCase(m_word.charAt(0));
        m_ie.second = Character.toLowerCase(m_word.charAt(1));
        if(!m_index.getIndexes(m_ie))
            return;
        Vector<WordEntry> ar = readWords(m_word);
        if(ar==null)
            return;
        m_handler.removeMessages(MSG_GET_WORDS);
        m_handler.sendMessage(m_handler.obtainMessage(MSG_GET_WORDS, ar));
    }
/** Отмена синхронной обработки */    
    void cancelSync(boolean bCancel)
    {
        m_bCancel = bCancel;
    }
/** Если true - текущая задача прервется, как только */    
    boolean m_bCancel = false;
/** Сравнение слов по частоте для сортировки */    
    Comparator<WordEntry> m_wordsComparator = new Comparator<WordEntry>()
    {
        @Override
        public int compare(WordEntry object1, WordEntry object2)
        {
            if(object1.compareType>object2.compareType)return 1;
            if(object1.compareType<object2.compareType)return -1;
            if(object1.freq<object2.freq)return 1;
            if(object1.freq>object2.freq)return -1;
            return 0;
        }
        
    };
    final int getMinFreq(Vector<WordEntry> ar)
    {
        int ret = 10000000;
        for(WordEntry we:ar)
        {
            if(we.freq<ret)
                ret = we.freq;
        }
        return ret==10000000?1:ret;
    }
/** Возвращает позицию элемента с минимальной частотой в массиве ar 
*@param ar Массив слов
*@return Возвращает позицию элемента с минимальной частотой */
    final int getMinFreqPos(Vector<WordEntry> ar)
    {
        int ret = 10000000;
        int pos = -1;
        int i = 0;
        for(WordEntry we:ar)
        {
            if(we.freq<ret)
            {
                ret = we.freq;
                pos = i;
            }
            ++i;
        }
        return pos;
    }
/** Устанавливает слово we в позицию с минимальной частотой 
*@param ar Массив слов
*@param we Новое слово
*@return Возвращает текущую минимальную частоту в массиве */
    final int setAtMinFreq(Vector<WordEntry>ar,WordEntry we)
    {
        int pos = getMinFreqPos(ar);
        if(pos>-1)
            ar.set(pos, we);
        return getMinFreq(ar);
    }
    String m_ret[] = new String[m_wordsLimit];
/** Основная функция для считывания из словаря 
*@param word Пользовательское слово
*@return Массив подходящих слов в словаре */
    private Vector<WordEntry> readWords(String word)
    {
        try{
            int cs = TextTools.getTextCase(word);
            String lc = word.toLowerCase();
            IWords.TextFileWords wi = new IWords.TextFileWords();
            wi.open(m_file, m_ie, lc);
            Vector<WordEntry> ar = new Vector<WordEntry>(m_wordsLimit); 
            int minF = 0;
            boolean bFull = false;
            while(!m_bCancel)
            {
                WordEntry we = wi.getNextWordEntry(minF, bFull);
                if(we==null)
                {
                    if(wi.m_bHasNext)
                        continue;
                    else
                        break;
                }
                if(!bFull)
                {
                    ar.add(we);
                    bFull = ar.size()==m_wordsLimit;
                    if(bFull)
                        minF = getMinFreq(ar);
                }
                else if(we.freq>minF)
                {
                    setAtMinFreq(ar, we);
                }
            }
            if(m_bCancel)
                return null;
            return postProcessWords(ar,cs);
        }
        catch (Throwable e) 
        {
            e.printStackTrace();
        }
        return null;
    }
/** Сортировка элементов, выставление регистра cs 
*@param ar Массив для обработки
*@param cs Изначальный регистр слова
*@return Возвращает обработанный массив */
    Vector<WordEntry> postProcessWords(Vector<WordEntry>ar,int cs)
    {
        Collections.sort(ar, m_wordsComparator);
        if(ar.size()<1||ar.get(0).compareType!=TextTools.COMPARE_TYPE_EQUAL)
        {
            WordEntry we = new WordEntry();
            we.word = m_word;
            we.freq = 1;
            we.compareType = TextTools.COMPARE_TYPE_NONE;
            ar.add(0,we);
        }
        for(WordEntry we:ar)
            we.word = TextTools.changeCase(we.word, cs);
        return ar;
//        for(int i=0;i<m_wordsLimit;i++)
//        {
//            if(i<ar.size())
//                m_ret[i]=TextTools.changeCase(ar.get(i).word, cs);
//            else
//                m_ret[i]=null;
//        }
//        return m_ret;
    }
}